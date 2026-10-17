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
    private final RecordingAccess recordingAccess;
    private final WorkspaceRepository workspaces;
    private final RecordingSessionRepository recordings;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingTranscriptionRepository transcriptions;
    private final TranscriptRepository transcripts;
    private final TranscriptSegmentRepository segments;
    private final DocumentGenerationIntakeService documentIntake;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAutomatic(
            long workspaceId,
            long recordingId
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.accept",
                workspaceId,
                recordingId,
                null,
                null
        );
        trace.call(() -> {
            Optional<RecordingTranscription> existing = transcriptions.findByRecordingSessionId(recordingId);
            if (existing.isPresent()) {
                trace.attempt(
                        existing.orElseThrow()
                                .getAttemptId()
                )
                        .outcome("replayed");
                return null;
            }
            transcriptions.lockAdmission();
            Instant now = now();
            RecordingTranscription transcription = RecordingTranscription.processing(
                    recordingId,
                    UUID.randomUUID(),
                    now
            );
            trace.attempt(transcription.getAttemptId())
                    .outcome("accepted");
            if (transcriptions.findProcessing()
                    .isPresent()) {
                transcription.fail(
                        transcription.getAttemptId(),
                        "BUSY"
                );
                trace.outcome("rejected")
                        .error("BUSY");
            }
            transcriptions.saveAndFlush(transcription);
            return null;
        });
    }

    @Transactional
    public RecordingTranscriptionRetryResult retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.retry_reserve",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            recordingAccess.loadOwnedForUpdate(
                    workspaceId,
                    memberId,
                    recordingId
            )
                    .validateEnded();
            RecordingAudioUpload upload = uploads.findByRecordingId(recordingId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
            trace.upload(upload.getId());
            upload.validateAudioAvailable();
            RecordingTranscription transcription = transcriptions.findByRecordingSessionIdForUpdate(recordingId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
            trace.attempt(transcription.getAttemptId());
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
            trace.attempt(transcription.getAttemptId())
                    .outcome("accepted");
            return new RecordingTranscriptionRetryResult(
                    recordingId,
                    transcription.getAttemptId(),
                    transcription.getStatus()
            );
        });
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
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.persist",
                execution.workspaceId(),
                execution.recordingId(),
                null,
                execution.attemptId()
        );
        trace.call(() -> {
            boolean workspaceAvailable = workspaces.findByIdForUpdate(execution.workspaceId())
                    .isPresent();
            boolean recordingAvailable = recordings.findByIdForUpdate(execution.recordingId())
                    .filter(value -> value.isTranscribableIn(execution.workspaceId()))
                    .isPresent();
            Optional<RecordingTranscription> current = transcriptions
                    .findByRecordingSessionIdForUpdate(execution.recordingId())
                    .filter(value -> value.isProcessing(execution.attemptId()));
            if (current.isEmpty()) {
                trace.outcome("ignored");
                return null;
            }
            RecordingTranscription transcription = current.orElseThrow();
            if (!workspaceAvailable || !recordingAvailable || !hasAvailableUpload(execution.recordingId())) {
                transcription.fail(
                        execution.attemptId(),
                        "RECORDING_UNAVAILABLE"
                );
                trace.outcome("failed")
                        .error("RECORDING_UNAVAILABLE");
                return null;
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
            trace.outcome("completed");
            return null;
        });
    }

    @Transactional
    public void fail(
            RecordingTranscriptionExecution execution,
            String failureCode
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.persist",
                execution.workspaceId(),
                execution.recordingId(),
                null,
                execution.attemptId()
        );
        trace.call(() -> {
            workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId());
            recordings.findByIdForUpdate(execution.recordingId());
            Optional<RecordingTranscription> current = transcriptions
                    .findByRecordingSessionIdForUpdate(execution.recordingId())
                    .filter(value -> value.isProcessing(execution.attemptId()));
            if (current.isEmpty()) {
                trace.outcome("ignored");
                return null;
            }
            current.orElseThrow()
                    .fail(
                            execution.attemptId(),
                            failureCode
                    );
            trace.outcome("failed")
                    .error(failureCode);
            return null;
        });
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
