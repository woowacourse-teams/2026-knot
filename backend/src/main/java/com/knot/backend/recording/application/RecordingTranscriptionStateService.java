package com.knot.backend.recording.application;

import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionSnapshot;
import com.knot.backend.recording.domain.RecordingAudioUpload;
import com.knot.backend.recording.domain.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import com.knot.backend.recording.domain.RecordingTranscription;
import com.knot.backend.recording.domain.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.Transcript;
import com.knot.backend.recording.domain.TranscriptRepository;
import com.knot.backend.recording.domain.TranscriptSegment;
import com.knot.backend.recording.domain.TranscriptSegmentRepository;
import com.knot.backend.recording.domain.TranscriptUtterance;
import com.knot.backend.workspace.domain.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingTranscriptionStateService {
    private final RecordingWorkspaceAccessValidator access;
    private final WorkspaceRepository workspaces;
    private final RecordingSessionRepository recordings;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingTranscriptionRepository transcriptions;
    private final TranscriptRepository transcripts;
    private final TranscriptSegmentRepository segments;
    private final DocumentGenerationIntakeService documentIntake;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAutomatic(long recordingId) {
        if (transcriptions.findByRecordingSessionId(recordingId)
                .isPresent()) {
            return;
        }
        transcriptions.lockAdmission();
        Instant now = now();
        RecordingTranscription transcription = RecordingTranscription.processing(
                recordingId,
                UUID.randomUUID(),
                now
        );
        if (transcriptions.findProcessing()
                .isPresent()) {
            transcription.fail(
                    transcription.getAttemptId(),
                    "BUSY"
            );
        }
        transcriptions.saveAndFlush(transcription);
    }

    @Transactional
    public RecordingTranscriptionRetryResult retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        access.validateAndLock(
                workspaceId,
                memberId
        );
        RecordingSession recording = recordings.findByIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        recording.validateControlledBy(
                workspaceId,
                memberId
        );
        recording.validateEnded();
        RecordingAudioUpload upload = uploads.findByRecordingId(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        upload.validateAudioAvailable();
        RecordingTranscription transcription = transcriptions.findByRecordingSessionIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
        transcription.validateRetryable();
        transcriptions.lockAdmission();
        if (transcriptions.findProcessing()
                .isPresent()) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_BUSY);
        }
        transcription.retry(
                UUID.randomUUID(),
                now()
        );
        return new RecordingTranscriptionRetryResult(
                recordingId,
                transcription.getAttemptId(),
                transcription.getStatus()
        );
    }

    @Transactional(readOnly = true)
    public Optional<RecordingTranscriptionExecution> findProcessing() {
        return transcriptions.findProcessing()
                .map(this::execution);
    }

    @Transactional(readOnly = true)
    public RecordingTranscriptionSnapshot find(long recordingId) {
        return transcriptions.findByRecordingSessionId(recordingId)
                .map(
                        value -> new RecordingTranscriptionSnapshot(
                                value.getAttemptId(),
                                value.getStatus(),
                                value.getFailureCode()
                        )
                )
                .orElse(null);
    }

    @Transactional
    public void complete(
            RecordingTranscriptionExecution execution,
            AiTranscript result
    ) {
        boolean workspaceAvailable = workspaces.findByIdForUpdate(execution.workspaceId())
                .isPresent();
        boolean recordingAvailable = recordings.findByIdForUpdate(execution.recordingId())
                .filter(value -> value.isTranscribableIn(execution.workspaceId()))
                .isPresent();
        Optional<RecordingTranscription> current = transcriptions
                .findByRecordingSessionIdForUpdate(execution.recordingId())
                .filter(value -> value.isProcessing(execution.attemptId()));
        if (current.isEmpty()) {
            return;
        }
        RecordingTranscription transcription = current.orElseThrow();
        if (!workspaceAvailable || !recordingAvailable || !hasAvailableUpload(execution.recordingId())) {
            transcription.fail(
                    execution.attemptId(),
                    "RECORDING_UNAVAILABLE"
            );
            return;
        }
        List<TranscriptUtterance> utterances = result.toUtterances();
        Transcript transcript = transcripts.saveAndFlush(
                Transcript.create(
                        execution.recordingId(),
                        result.rawWhisperJson(),
                        now()
                )
        );
        segments.saveAll(
                TranscriptSegment.createAll(
                        transcript.getId(),
                        utterances
                )
        );
        documentIntake.acceptCompletedTranscript(
                execution.workspaceId(),
                execution.recordingId(),
                transcript.getId()
        );
        transcription.succeed(execution.attemptId());
    }

    @Transactional
    public void fail(
            RecordingTranscriptionExecution execution,
            String failureCode
    ) {
        workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId());
        recordings.findByIdForUpdate(execution.recordingId());
        transcriptions.findByRecordingSessionIdForUpdate(execution.recordingId())
                .ifPresent(
                        value -> value.fail(
                                execution.attemptId(),
                                failureCode
                        )
                );
    }

    @Transactional
    public void recoverProcessing() {
        transcriptions.findProcessing()
                .ifPresent(
                        value -> fail(
                                execution(value),
                                "SERVER_RESTARTED"
                        )
                );
    }

    private RecordingTranscriptionExecution execution(RecordingTranscription transcription) {
        RecordingSession recording = recordings.findById(transcription.getRecordingSessionId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        RecordingAudioUpload upload = uploads.findByRecordingId(recording.getId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        return new RecordingTranscriptionExecution(
                recording.getWorkspaceId(),
                recording.getId(),
                transcription.getAttemptId(),
                upload.getStorageKey(),
                upload.getStorageVersionId(),
                upload.getContentType(),
                upload.getContentLength(),
                transcription.getStartedAt()
        );
    }

    private boolean hasAvailableUpload(long recordingId) {
        return uploads.findByRecordingId(recordingId)
                .filter(RecordingAudioUpload::isAudioAvailable)
                .isPresent();
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
