package com.knot.backend.recording.application;

import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionSnapshot;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RemoteObservation;
import java.time.Duration;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.model.Transcript;
import com.knot.backend.recording.domain.repository.TranscriptRepository;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.recording.domain.model.TranscriptUtterance;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
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
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingAudioUploadRepository recordingAudioUploadRepository;
    private final RecordingTranscriptionRepository recordingTranscriptionRepository;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final DocumentGenerationIntakeService documentGenerationIntakeService;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAutomatic(long recordingId) {
        if (recordingTranscriptionRepository.findByRecordingSessionId(recordingId)
                .isPresent()) {
            return;
        }
        RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        validateAvailableUpload(upload);
        RecordingTranscription transcription = RecordingTranscription.queued(
                recordingId,
                UUID.randomUUID(),
                now(),
                upload.getStorageKey(),
                upload.getStorageVersionId(),
                upload.getContentType(),
                upload.getContentLength(),
                null
        );
        recordingTranscriptionRepository.saveAndFlush(transcription);
    }

    @Transactional
    public RecordingTranscriptionRetryResult retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.loadOwnedForUpdate(
                workspaceId,
                memberId,
                recordingId
        )
                .validateEnded();
        RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        validateAvailableUpload(upload);
        RecordingTranscription transcription = recordingTranscriptionRepository
                .findByRecordingSessionIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
        transcription.validateRetryable();
        if (transcription.isOutcomeUncertain()) {
            throw new RecordingException(RecordingErrorCode.AI_OUTCOME_UNKNOWN);
        }
        transcription.enqueueRetry(
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
        return recordingTranscriptionRepository.findProcessing()
                .map(this::createExecutionContext);
    }

    @Transactional(readOnly = true)
    public RecordingTranscriptionSnapshot findSnapshot(long recordingId) {
        return recordingTranscriptionRepository.findByRecordingSessionId(recordingId)
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
            RecordingTranscriptionExecution executionContext,
            AiTranscript result
    ) {
        boolean workspaceAvailable = workspaceRepository.findByIdForUpdate(executionContext.workspaceId())
                .isPresent();
        boolean recordingAvailable = recordingSessionRepository.findByIdForUpdate(executionContext.recordingId())
                .filter(value -> value.isTranscribableIn(executionContext.workspaceId()))
                .filter(
                        value -> workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                                executionContext.workspaceId(),
                                value.getMemberId()
                        )
                )
                .isPresent();
        Optional<RecordingTranscription> processingAttempt = recordingTranscriptionRepository
                .findByRecordingSessionIdForUpdate(executionContext.recordingId())
                .filter(
                        value -> owns(
                                value,
                                executionContext
                        )
                );
        if (processingAttempt.isEmpty()) {
            return;
        }
        RecordingTranscription transcription = processingAttempt.orElseThrow();
        if (!workspaceAvailable || !recordingAvailable || !hasAvailableUpload(executionContext.recordingId())) {
            transcription.fail(
                    executionContext.attemptId(),
                    "RECORDING_UNAVAILABLE"
            );
            return;
        }
        persistTranscript(
                executionContext.workspaceId(),
                executionContext.recordingId(),
                transcription,
                result
        );
    }

    private void persistTranscript(
            long workspaceId,
            long recordingId,
            RecordingTranscription transcription,
            AiTranscript result
    ) {
        List<TranscriptUtterance> utterances = result.toUtterances();
        // 성공 snapshot 재채택도 flush 전에 terminal 상태로 만들어 다른 실행 슬롯과 충돌하지 않는다.
        // 아래 원문·발화·문서 접수가 실패하면 같은 트랜잭션에서 상태도 롤백한다.
        transcription.succeed(transcription.getAttemptId());
        Transcript transcript = transcriptRepository.saveAndFlush(
                Transcript.create(
                        recordingId,
                        result.rawWhisperJson(),
                        now()
                )
        );
        transcriptSegmentRepository.saveAll(
                TranscriptSegment.createAll(
                        transcript.getId(),
                        utterances
                )
        );
        documentGenerationIntakeService.acceptCompletedTranscript(
                workspaceId,
                recordingId,
                transcript.getId()
        );
    }

    @Transactional
    public void fail(
            RecordingTranscriptionExecution executionContext,
            String failureCode
    ) {
        workspaceRepository.findIncludingDeletedByIdForUpdate(executionContext.workspaceId());
        recordingSessionRepository.findByIdForUpdate(executionContext.recordingId());
        recordingTranscriptionRepository.findByRecordingSessionIdForUpdate(executionContext.recordingId())
                .ifPresent(value -> {
                    if (owns(
                            value,
                            executionContext
                    )) {
                        value.fail(
                                executionContext.attemptId(),
                                failureCode
                        );
                    }
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RecordingTranscriptionExecution> claimNext(
            UUID ownerToken,
            Duration lease
    ) {
        recordingTranscriptionRepository.lockAdmission();
        Optional<RecordingTranscription> next = findClaimCandidate();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        RecordingTranscription candidate = next.orElseThrow();
        candidate.claim(
                ownerToken,
                now(),
                lease
        );
        recordingTranscriptionRepository.saveAndFlush(candidate);
        return Optional.of(createExecutionContext(candidate));
    }

    private Optional<RecordingTranscription> findClaimCandidate() {
        Optional<RecordingTranscription> processing = recordingTranscriptionRepository.findProcessingForUpdate();
        if (processing.isEmpty()) {
            return recordingTranscriptionRepository.findNextQueuedForUpdate(now());
        }
        RecordingTranscription current = processing.orElseThrow();
        if (current.getLeaseUntil() != null && now().isBefore(current.getLeaseUntil())) {
            return Optional.empty();
        }
        return processing;
    }

    @Transactional
    public boolean renew(
            RecordingTranscriptionExecution execution,
            Duration lease
    ) {
        Optional<RecordingTranscription> value = owned(execution);
        value.ifPresent(
                row -> row.renew(
                        now(),
                        lease
                )
        );
        return value.isPresent();
    }

    @Transactional
    public Optional<RecordingTranscriptionExecution> prepare(RecordingTranscriptionExecution execution) {
        boolean workspaceAvailable = workspaceRepository.findByIdForUpdate(execution.workspaceId())
                .isPresent();
        boolean recordingAvailable = recordingSessionRepository.findByIdForUpdate(execution.recordingId())
                .filter(row -> row.isTranscribableIn(execution.workspaceId()))
                .filter(
                        row -> workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                                execution.workspaceId(),
                                row.getMemberId()
                        )
                )
                .isPresent();
        Optional<RecordingTranscription> value = owned(execution);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        RecordingTranscription row = value.orElseThrow();
        if (!workspaceAvailable || !recordingAvailable || !hasAvailableUpload(execution.recordingId())
                || row.getSourceStorageKey() == null || row.getSourceVersionId() == null
                || "null".equals(row.getSourceVersionId())) {
            row.fail(
                    execution.attemptId(),
                    "RECORDING_UNAVAILABLE"
            );
            return Optional.empty();
        }
        return Optional.of(createExecutionContext(row));
    }

    @Transactional
    public Optional<Instant> restoreLegacyDeadline(
            RecordingTranscriptionExecution execution,
            Duration timeout
    ) {
        Optional<RecordingTranscription> value = owned(execution);
        value.ifPresent(row -> row.restoreLegacyDeadline(timeout));
        return value.map(RecordingTranscription::getExecutionDeadline);
    }

    @Transactional
    public Optional<Instant> beginRemotePost(
            RecordingTranscriptionExecution execution,
            Duration timeout
    ) {
        Optional<RecordingTranscription> value = owned(execution);
        value.ifPresent(
                row -> row.beginRemotePost(
                        now(),
                        timeout
                )
        );
        return value.map(RecordingTranscription::getExecutionDeadline);
    }

    @Transactional
    public void observe(
            RecordingTranscriptionExecution execution,
            RemoteObservation observation
    ) {
        owned(execution).ifPresent(row -> row.observe(observation));
    }

    @Transactional
    public void requeue(
            RecordingTranscriptionExecution execution,
            Duration delay
    ) {
        owned(execution).ifPresent(row -> row.requeue(now().plus(delay)));
    }

    @Transactional
    public void deferReadiness(
            RecordingTranscriptionExecution execution,
            Duration maximumWait,
            boolean firstPostRejected
    ) {
        owned(execution).ifPresent(row -> {
            if (firstPostRejected) {
                row.observe(RemoteObservation.NOT_ACCEPTED);
            }
            if (!row.deferReadiness(
                    now(),
                    maximumWait
            )) {
                row.fail(
                        execution.attemptId(),
                        "AI_UNAVAILABLE"
                );
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<RecordingTranscriptionExecution> findUncertainRetry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        RecordingSession session = recordingSessionRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        session.validateControlledBy(
                workspaceId,
                memberId
        );
        RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        validateAvailableUpload(upload);
        return recordingTranscriptionRepository.findByRecordingSessionId(recordingId)
                .filter(RecordingTranscription::isOutcomeUncertain)
                .map(this::createExecutionContext);
    }

    @Transactional
    public RecordingTranscriptionRetryResult resolveUncertainRetry(
            long workspaceId,
            long memberId,
            RecordingTranscriptionExecution previous,
            boolean resumeExisting
    ) {
        recordingAccess.loadOwnedForUpdate(
                workspaceId,
                memberId,
                previous.recordingId()
        )
                .validateEnded();
        RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(previous.recordingId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        validateAvailableUpload(upload);
        RecordingTranscription row = recordingTranscriptionRepository
                .findByRecordingSessionIdForUpdate(previous.recordingId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
        if (!row.isOutcomeUncertain() || !row.getAttemptId()
                .equals(previous.attemptId())) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED);
        }
        if (resumeExisting) {
            row.resumeUncertain(now());
            return new RecordingTranscriptionRetryResult(
                    previous.recordingId(),
                    row.getAttemptId(),
                    row.getStatus()
            );
        }
        row.enqueueRetry(
                UUID.randomUUID(),
                now()
        );
        return new RecordingTranscriptionRetryResult(
                previous.recordingId(),
                row.getAttemptId(),
                row.getStatus()
        );
    }

    @Transactional
    public RecordingTranscriptionRetryResult adoptUncertainResult(
            long workspaceId,
            long memberId,
            RecordingTranscriptionExecution previous,
            AiTranscript result
    ) {
        recordingAccess.loadOwnedForUpdate(
                workspaceId,
                memberId,
                previous.recordingId()
        )
                .validateEnded();
        RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(previous.recordingId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
        validateAvailableUpload(upload);
        RecordingTranscription row = recordingTranscriptionRepository
                .findByRecordingSessionIdForUpdate(previous.recordingId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
        if (!row.isOutcomeUncertain() || !row.getAttemptId()
                .equals(previous.attemptId())) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED);
        }
        // 이미 받은 성공 결과만 채택한다. 원격 실행이나 PROCESSING 행을 새로 접수하지 않는다.
        row.resumeUncertain(now());
        UUID adoptionOwner = UUID.randomUUID();
        row.claim(
                adoptionOwner,
                now(),
                Duration.ofSeconds(60)
        );
        if (!row.isOwnedBy(
                previous.attemptId(),
                adoptionOwner,
                row.getClaimEpoch(),
                now()
        )) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED);
        }
        persistTranscript(
                workspaceId,
                previous.recordingId(),
                row,
                result
        );
        return new RecordingTranscriptionRetryResult(
                previous.recordingId(),
                row.getAttemptId(),
                row.getStatus()
        );
    }

    private Optional<RecordingTranscription> owned(RecordingTranscriptionExecution execution) {
        return recordingTranscriptionRepository.findByRecordingSessionIdForUpdate(execution.recordingId())
                .filter(
                        row -> owns(
                                row,
                                execution
                        )
                );
    }

    private boolean owns(
            RecordingTranscription row,
            RecordingTranscriptionExecution execution
    ) {
        return row.isOwnedBy(
                execution.attemptId(),
                execution.ownerToken(),
                execution.claimEpoch(),
                now()
        );
    }

    private RecordingTranscriptionExecution createExecutionContext(RecordingTranscription transcription) {
        RecordingSession recording = recordingSessionRepository.findById(transcription.getRecordingSessionId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        return new RecordingTranscriptionExecution(
                recording.getWorkspaceId(),
                recording.getId(),
                transcription.getAttemptId(),
                transcription.getSourceStorageKey(),
                transcription.getSourceVersionId(),
                transcription.getSourceContentType(),
                Optional.ofNullable(transcription.getSourceContentLength())
                        .orElse(0L),
                transcription.getStartedAt(),
                transcription.getOwnerToken(),
                transcription.getClaimEpoch(),
                transcription.getExecutionDeadline(),
                transcription.getRemoteObservation(),
                transcription.getSpeakerCount()
        );
    }

    private void validateAvailableUpload(RecordingAudioUpload upload) {
        upload.validateAudioAvailable();
        if (upload.isRetentionExpired(now())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        }
    }

    private boolean hasAvailableUpload(long recordingId) {
        return recordingAudioUploadRepository.findByRecordingId(recordingId)
                .filter(RecordingAudioUpload::isAudioAvailable)
                .filter(upload -> !upload.isRetentionExpired(now()))
                .isPresent();
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
