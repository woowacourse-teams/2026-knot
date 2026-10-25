package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.PreparedWindowSource;
import com.knot.backend.recording.domain.model.InputPreparationStatus;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupScope;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import com.knot.backend.recording.domain.model.TranscriptionWindowStatus;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class IncrementalTranscriptionCoordinator {
    private static final double SIMILARITY_THRESHOLD = .65;

    private final RecordingTranscriptionInputRepository inputs;
    private final RecordingSessionRepository sessions;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioFinalizationRepository finalizations;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingAudioCleanupRepository cleanup;
    private final RecordingTranscriptionWindowRepository windows;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final RecordingTranscriptionWindowService windowService;
    private final RecordingTranscriptionWindowPlanner planner;
    private final IncrementalTranscriptMerger merger;
    private final RecordingTranscriptionStateService state;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    public void advance(long recordingId) {
        Optional<Long> workspaceId = sessions.findWorkspaceIdById(recordingId);
        if (workspaceId.isEmpty() || workspaces.findIncludingDeletedByIdForUpdate(workspaceId.orElseThrow())
                .isEmpty()) {
            return;
        }
        Optional<RecordingSession> found = sessions.findByIdForUpdate(recordingId);
        if (found.isEmpty()) {
            return;
        }
        RecordingSession session = found.orElseThrow();
        Optional<RecordingTranscriptionInput> prepared = inputs.findByRecordingIdForUpdate(recordingId)
                .filter(input -> input.getStatus() == InputPreparationStatus.READY);
        boolean livePipeline = session.getIncrementalPipelineVersion() != null
                && session.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1;
        if (!livePipeline && prepared.isEmpty()) {
            return;
        }
        Optional<RecordingAudioStream> foundStream = streams.findByRecordingIdForUpdate(recordingId);
        Optional<RecordingAudioFinalization> finalization = finalizations.findByRecordingIdForUpdate(recordingId);
        boolean revoked = uploads.findByRecordingIdForUpdate(recordingId)
                .map(upload -> upload.getRevokedAt() != null || upload.getDeletedAt() != null)
                .orElse(false);
        boolean purged = cleanup.findForUpdate(recordingId)
                .map(task -> task.getScope() == RecordingAudioCleanupScope.ALL)
                .orElse(false);
        if ((livePipeline && foundStream.isEmpty()) || revoked || purged || !available(session)) {
            return;
        }
        RecordingAudioStream stream = foundStream.orElse(null);
        if (livePipeline && session.isActive() && !stream.isFrozen() && !stream.isSealed()) {
            windowService.registerAvailable(
                    recordingId,
                    TranscriptionWindowShape.of(session.getIncrementalPipelineVersion())
            );
            return;
        }
        if (session.getStatus() != RecordingStatus.ENDED) {
            return;
        }
        List<RecordingTranscriptionWindow> planned;
        long totalFrames;
        TranscriptionWindowShape shape;
        if (livePipeline) {
            if (finalization.isEmpty() || finalization.orElseThrow()
                    .getStatus() != AudioFinalizationStatus.READY) {
                return;
            }
            totalFrames = stream.getTotalFrameCount();
            shape = TranscriptionWindowShape.of(session.getIncrementalPipelineVersion());
            planned = planner.planFinal(
                    recordingId,
                    shape,
                    totalFrames,
                    frozenParts(finalization.orElseThrow()),
                    clock.instant()
            );
        } else {
            RecordingTranscriptionInput input = prepared.orElseThrow();
            shape = preparedShape(recordingId);
            totalFrames = input.getTotalFrames();
            planned = planner.planPrepared(
                    recordingId,
                    shape,
                    new PreparedWindowSource(
                            input.getStorageKey(),
                            input.getStorageVersionId(),
                            input.getChecksumSha256(),
                            input.getByteLength(),
                            totalFrames
                    ),
                    clock.instant()
            );
        }
        String pipelineVersion = shape.pipelineVersion();
        for (RecordingTranscriptionWindow candidate : planned) {
            Optional<RecordingTranscriptionWindow> existing = windows.findByRecordingIdAndPipelineVersionAndWindowIndex(
                    recordingId,
                    pipelineVersion,
                    candidate.getWindowIndex()
            );
            if (existing.isEmpty()) {
                windows.save(candidate);
            } else {
                validateSameInput(
                        existing.orElseThrow(),
                        candidate
                );
            }
        }
        List<RecordingTranscriptionWindow> completed = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                recordingId,
                pipelineVersion
        );
        for (RecordingTranscriptionWindow window : completed) {
            windows.findByIdForUpdate(window.getId());
        }
        if (completed.stream()
                .anyMatch(window -> window.getStatus() == TranscriptionWindowStatus.FAILED)) {
            state.failIncremental(
                    recordingId,
                    "INCREMENTAL_WINDOW_FAILED"
            );
        } else if (!completed.isEmpty() && completed.stream()
                .allMatch(window -> window.getStatus() == TranscriptionWindowStatus.SUCCEEDED)) {
            com.knot.backend.recording.application.dto.result.AiTranscript result;
            try {
                result = merger.merge(
                        completed,
                        totalFrames,
                        SIMILARITY_THRESHOLD,
                        shape.seamAligned()
                );
            } catch (RecordingException exception) {
                state.failIncremental(
                        recordingId,
                        "INCREMENTAL_MERGE_FAILED"
                );
                return;
            }
            state.completeIncremental(
                    recordingId,
                    result
            );
        }
    }

    /** 배포 전에 30초 구간을 만들기 시작한 준비 입력은 같은 버전으로 끝낸다. */
    private TranscriptionWindowShape preparedShape(long recordingId) {
        boolean started = !windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                recordingId,
                TranscriptionWindowShape.PREPARED_30_SECONDS.pipelineVersion()
        )
                .isEmpty();
        if (started) {
            return TranscriptionWindowShape.PREPARED_30_SECONDS;
        }
        return TranscriptionWindowShape.PREPARED_TWO_MINUTES;
    }

    private List<AudioManifestPart> frozenParts(RecordingAudioFinalization finalization) {
        try {
            AudioManifestPart[] parts = mapper.readerFor(AudioManifestPart[].class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(finalization.getManifestJson());
            if (parts == null) {
                throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
            }
            return Arrays.asList(parts);
        } catch (JacksonException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private boolean available(RecordingSession session) {
        boolean recordingAvailable = session.isActive()
                || (session.getStatus() == RecordingStatus.ENDED && session.getEndedAt() != null);
        return recordingAvailable && workspaces.findById(session.getWorkspaceId())
                .isPresent()
                && members.existsByWorkspaceIdAndMemberId(
                        session.getWorkspaceId(),
                        session.getMemberId()
                );
    }

    private void validateSameInput(
            RecordingTranscriptionWindow existing,
            RecordingTranscriptionWindow candidate
    ) {
        if (existing.getCoreStartFrame() != candidate.getCoreStartFrame()
                || existing.getCoreEndFrame() != candidate.getCoreEndFrame()
                || existing.getInputStartFrame() != candidate.getInputStartFrame()
                || existing.getInputEndFrame() != candidate.getInputEndFrame() || !existing.getManifestSha256()
                        .equals(candidate.getManifestSha256())) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }
}
