package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.InputPreparationStatus;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupScope;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import java.util.function.Supplier;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecordingTranscriptionWindowService {
    private final RecordingTranscriptionInputRepository inputs;
    private final RecordingSessionRepository sessions;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioChunkRepository chunks;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingAudioCleanupRepository cleanup;
    private final RecordingTranscriptionWindowRepository windows;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final RecordingTranscriptionWindowPlanner planner;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository transcriptions;
    private final com.knot.backend.recording.domain.repository.IncrementalTranscriptionQueue queue;
    private final com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository finalizations;

    @Transactional
    public int registerAvailable(
            long recordingId,
            String pipelineVersion,
            long coreFrames,
            long contextFrames
    ) {
        return register(
                recordingId,
                pipelineVersion,
                () -> planner.plan(
                        recordingId,
                        pipelineVersion,
                        coreFrames,
                        contextFrames,
                        chunks.findAllByRecordingIdOrderBySequence(recordingId),
                        now()
                )
        );
    }

    @Transactional
    public int registerAvailable(
            long recordingId,
            TranscriptionWindowShape shape
    ) {
        return register(
                recordingId,
                shape.pipelineVersion(),
                () -> planner.plan(
                        recordingId,
                        shape,
                        chunks.findAllByRecordingIdOrderBySequence(recordingId),
                        now()
                )
        );
    }

    private int register(
            long recordingId,
            String pipelineVersion,
            Supplier<List<RecordingTranscriptionWindow>> planned
    ) {
        RecordingSession session = lockSession(recordingId);
        RecordingAudioStream stream = lockStream(recordingId);
        if (!available(session) || !session.isActive() || stream.isSealed() || stream.isFrozen()) {
            return 0;
        }
        int registered = 0;
        for (RecordingTranscriptionWindow candidate : planned.get()) {
            Optional<RecordingTranscriptionWindow> existing = windows.findByRecordingIdAndPipelineVersionAndWindowIndex(
                    recordingId,
                    pipelineVersion,
                    candidate.getWindowIndex()
            );
            if (existing.isPresent()) {
                validateSameInput(
                        existing.orElseThrow(),
                        candidate
                );
            } else {
                windows.save(candidate);
                registered++;
            }
        }
        return registered;
    }

    @Transactional
    public Optional<TranscriptionWindowClaim> claim(
            long recordingId,
            long windowId,
            Duration lease,
            Duration executionTimeout
    ) {
        transcriptions.lockAdmission();
        if (transcriptions.findProcessing()
                .isPresent()) {
            return Optional.empty();
        }
        RecordingSession session = lockSession(recordingId);
        Optional<RecordingAudioStream> stream = streams.findByRecordingIdForUpdate(recordingId);
        if (!available(session) || !hasInput(
                session,
                stream.isPresent()
        )) {
            return Optional.empty();
        }
        RecordingTranscriptionWindow window = lockWindow(
                recordingId,
                windowId
        );
        if (stream.isPresent() && stream.orElseThrow()
                .isFrozen()
                && finalizations.findByRecordingIdForUpdate(recordingId)
                        .filter(job -> job.getStatus() == AudioFinalizationStatus.READY)
                        .isEmpty()) {
            return Optional.empty();
        }
        if (queue.hasProcessingWindow()
                && window.getStatus() != com.knot.backend.recording.domain.model.TranscriptionWindowStatus.PROCESSING) {
            return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        if (!window.claim(
                owner,
                now(),
                lease,
                executionTimeout
        )) {
            return Optional.empty();
        }
        return Optional.of(
                new TranscriptionWindowClaim(
                        recordingId,
                        windowId,
                        window.getAttemptId(),
                        owner,
                        window.getClaimEpoch(),
                        window.getManifestJson(),
                        window.getManifestSha256(),
                        window.getDecodePolicy()
                )
        );
    }

    @Transactional
    public Optional<com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource> source(
            TranscriptionWindowClaim claim
    ) {
        Optional<RecordingTranscriptionWindow> found = owned(claim);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordingTranscriptionWindow window = found.orElseThrow();
        Optional<RecordingTranscriptionInput> prepared = readyInput(claim.recordingId());
        if (prepared.isPresent()) {
            RecordingTranscriptionInput input = prepared.orElseThrow();
            if (!RecordingTranscriptionInput.windowPipelineVersions()
                    .contains(window.getPipelineVersion())) {
                throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
            }
            return Optional.of(
                    new TranscriptionWindowAudioSource(
                            window.getId(),
                            window.getRecordingId(),
                            window.getPipelineVersion(),
                            window.getManifestJson(),
                            window.getManifestSha256(),
                            input.getStorageKey(),
                            input.getStorageVersionId(),
                            input.getChecksumSha256(),
                            input.getByteLength()
                    )
            );
        }
        Optional<com.knot.backend.recording.domain.model.RecordingAudioFinalization> ready = finalizations
                .findByRecordingIdForUpdate(claim.recordingId())
                .filter(
                        job -> job.getStatus() == com.knot.backend.recording.domain.model.AudioFinalizationStatus.READY
                );
        if (ready.isEmpty()) {
            return Optional.of(
                    new com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource(
                            window.getId(),
                            window.getRecordingId(),
                            window.getPipelineVersion(),
                            window.getManifestJson(),
                            window.getManifestSha256(),
                            null,
                            null,
                            null,
                            0
                    )
            );
        }
        com.knot.backend.recording.domain.model.RecordingAudioFinalization finalization = ready.orElseThrow();
        com.knot.backend.recording.domain.model.AudioManifestPart[] frozen = mapper.readValue(
                finalization.getManifestJson(),
                com.knot.backend.recording.domain.model.AudioManifestPart[].class
        );
        com.knot.backend.recording.domain.model.AudioManifestPart[] parts = mapper.treeToValue(
                mapper.readTree(window.getManifestJson())
                        .path("parts"),
                com.knot.backend.recording.domain.model.AudioManifestPart[].class
        );
        java.util.List<com.knot.backend.recording.domain.model.AudioManifestPart> expected = java.util.Arrays
                .stream(frozen)
                .filter(
                        part -> part.sampleOffset() < window.getInputEndFrame()
                                && part.sampleOffset() + part.frameCount() > window.getInputStartFrame()
                )
                .toList();
        if (!expected.equals(java.util.Arrays.asList(parts))) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return Optional.of(
                new com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource(
                        window.getId(),
                        window.getRecordingId(),
                        window.getPipelineVersion(),
                        window.getManifestJson(),
                        window.getManifestSha256(),
                        finalization.getFinalKey(),
                        finalization.getFinalVersionId(),
                        finalization.getFinalChecksum(),
                        finalization.getFinalByteLength()
                )
        );
    }

    @Transactional
    public boolean expire(
            long recordingId,
            long windowId
    ) {
        RecordingSession session = lockSession(recordingId);
        Optional<RecordingAudioStream> stream = streams.findByRecordingIdForUpdate(recordingId);
        if (!available(session) || !hasInput(
                session,
                stream.isPresent()
        )) {
            return false;
        }
        return lockWindow(
                recordingId,
                windowId
        ).expire(now());
    }

    @Transactional
    public boolean renew(
            TranscriptionWindowClaim claim,
            Duration lease
    ) {
        Optional<RecordingTranscriptionWindow> window = owned(claim);
        return window.isPresent() && window.orElseThrow()
                .renew(
                        claim.attemptId(),
                        claim.ownerToken(),
                        claim.claimEpoch(),
                        now(),
                        lease
                );
    }

    @Transactional
    public boolean succeed(
            TranscriptionWindowClaim claim,
            int schemaVersion,
            String resultJson
    ) {
        Optional<RecordingTranscriptionWindow> window = owned(claim);
        if (window.isEmpty()) {
            return false;
        }
        validateJsonObject(resultJson);
        return window.orElseThrow()
                .succeed(
                        claim.attemptId(),
                        claim.ownerToken(),
                        claim.claimEpoch(),
                        now(),
                        schemaVersion,
                        resultJson
                );
    }

    @Transactional
    public boolean defer(
            TranscriptionWindowClaim claim,
            Instant nextRunAt,
            String failureCode
    ) {
        Optional<RecordingTranscriptionWindow> window = owned(claim);
        return window.isPresent() && window.orElseThrow()
                .defer(
                        claim.attemptId(),
                        claim.ownerToken(),
                        claim.claimEpoch(),
                        now(),
                        nextRunAt,
                        failureCode
                );
    }

    @Transactional
    public boolean fail(
            TranscriptionWindowClaim claim,
            String failureCode
    ) {
        Optional<RecordingTranscriptionWindow> window = owned(claim);
        return window.isPresent() && window.orElseThrow()
                .fail(
                        claim.attemptId(),
                        claim.ownerToken(),
                        claim.claimEpoch(),
                        now(),
                        failureCode
                );
    }

    private Optional<RecordingTranscriptionWindow> owned(TranscriptionWindowClaim claim) {
        RecordingSession session = lockSession(claim.recordingId());
        Optional<RecordingAudioStream> stream = streams.findByRecordingIdForUpdate(claim.recordingId());
        if (!available(session) || !hasInput(
                session,
                stream.isPresent()
        )) {
            return Optional.empty();
        }
        RecordingTranscriptionWindow window = lockWindow(
                claim.recordingId(),
                claim.windowId()
        );
        return window.isOwnedBy(
                claim.attemptId(),
                claim.ownerToken(),
                claim.claimEpoch(),
                now()
        ) ? Optional.of(window) : Optional.empty();
    }

    private RecordingSession lockSession(long recordingId) {
        long workspaceId = sessions.findWorkspaceIdById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        workspaces.findIncludingDeletedByIdForUpdate(workspaceId);
        return sessions.findByIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private RecordingAudioStream lockStream(long recordingId) {
        return streams.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private RecordingTranscriptionWindow lockWindow(
            long recordingId,
            long windowId
    ) {
        return windows.findByIdForUpdate(windowId)
                .filter(window -> window.getRecordingId() == recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private boolean available(RecordingSession session) {
        boolean revoked = uploads.findByRecordingIdForUpdate(session.getId())
                .map(upload -> upload.getRevokedAt() != null || upload.getDeletedAt() != null)
                .orElse(false);
        boolean purged = cleanup.findForUpdate(session.getId())
                .map(task -> task.getScope() == RecordingAudioCleanupScope.ALL)
                .orElse(false);
        boolean recordingAvailable = session.isActive()
                || (session.getStatus() == RecordingStatus.ENDED && session.getEndedAt() != null);
        return !revoked && !purged && recordingAvailable && workspaces.findById(session.getWorkspaceId())
                .isPresent()
                && members.existsByWorkspaceIdAndMemberId(
                        session.getWorkspaceId(),
                        session.getMemberId()
                );
    }

    private boolean hasInput(
            RecordingSession session,
            boolean hasStream
    ) {
        return (session.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1 && hasStream)
                || readyInput(session.getId()).isPresent();
    }

    private Optional<RecordingTranscriptionInput> readyInput(long recordingId) {
        return inputs.findByRecordingId(recordingId)
                .filter(input -> input.getStatus() == InputPreparationStatus.READY);
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

    private void validateJsonObject(String json) {
        if (json == null || json.length() > 1_048_576) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        try {
            if (!mapper.reader()
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json)
                    .isObject()) {
                throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
            }
        } catch (tools.jackson.core.JacksonException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
