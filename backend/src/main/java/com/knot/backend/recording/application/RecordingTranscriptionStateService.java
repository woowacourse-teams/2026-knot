package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
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
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.global.config.RecordingAudioChunksProperties;
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
    private final RecordingTranscriptionWindowRepository transcriptionWindowRepository;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final DocumentGenerationIntakeService documentGenerationIntakeService;
    private final Clock clock;
    private final RecordingTranscriptionInputRepository transcriptionInputs;
    private final RecordingAudioChunksProperties audioChunksProperties;
    private final RecordingAudioCleanupRepository audioCleanup;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAutomatic(long recordingId) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.accept",
                recordingSessionRepository.findById(recordingId)
                        .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND))
                        .getWorkspaceId(),
                recordingId,
                null,
                null
        );
        trace.call(() -> {
            Optional<RecordingTranscription> existing = recordingTranscriptionRepository
                    .findByRecordingSessionId(recordingId);
            if (existing.isPresent()) {
                trace.attempt(
                        existing.orElseThrow()
                                .getAttemptId()
                )
                        .outcome("replayed");
                return null;
            }
            RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
            trace.upload(upload.getId());
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
            trace.attempt(transcription.getAttemptId())
                    .outcome("accepted");
            recordingTranscriptionRepository.saveAndFlush(transcription);
            RecordingSession session = recordingSessionRepository.findById(recordingId)
                    .orElseThrow();
            if (session.getIncrementalPipelineVersion() == null && audioChunksProperties.incrementalEnabled()) {
                transcriptionInputs.save(
                        com.knot.backend.recording.domain.model.RecordingTranscriptionInput.queued(
                                recordingId,
                                upload.getStorageKey(),
                                upload.getStorageVersionId(),
                                upload.getContentType(),
                                upload.getContentLength(),
                                now()
                        )
                );
                audioCleanup.track(
                        recordingId,
                        now()
                );
            }

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
            RecordingSession session = recordingAccess.loadWorkspaceRecordingForUpdate(
                    workspaceId,
                    memberId,
                    recordingId
            );
            session.validateEnded();
            RecordingAudioUpload upload = recordingAudioUploadRepository.findByRecordingId(recordingId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND));
            trace.upload(upload.getId());
            validateAvailableUpload(upload);
            RecordingTranscription transcription = recordingTranscriptionRepository
                    .findByRecordingSessionIdForUpdate(recordingId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED));
            trace.attempt(transcription.getAttemptId());
            transcription.validateRetryable();
            if (transcription.isOutcomeUncertain()) {
                throw new RecordingException(RecordingErrorCode.AI_OUTCOME_UNKNOWN);
            }
            Optional<com.knot.backend.recording.domain.model.RecordingTranscriptionInput> prepared = transcriptionInputs
                    .findByRecordingIdForUpdate(recordingId);
            prepared.ifPresent(input -> input.retry(now()));
            String pipeline = session.getIncrementalPipelineVersion() != null
                    ? session.getIncrementalPipelineVersion()
                    : prepared.map(
                            com.knot.backend.recording.domain.model.RecordingTranscriptionInput::getPipelineVersion
                    )
                            .orElse(null);
            if (pipeline != null) {
                boolean regenerateSucceeded = "INCREMENTAL_MERGE_FAILED".equals(transcription.getFailureCode());
                for (RecordingTranscriptionWindow candidate : transcriptionWindowRepository
                        .findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                                recordingId,
                                pipeline
                        )) {
                    transcriptionWindowRepository.findByIdForUpdate(candidate.getId())
                            .ifPresent(
                                    window -> window.resetForExplicitRetry(
                                            UUID.randomUUID(),
                                            now(),
                                            regenerateSucceeded
                                    )
                            );
                }
            }
            transcription.enqueueRetry(
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
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.persist",
                executionContext.workspaceId(),
                executionContext.recordingId(),
                null,
                executionContext.attemptId()
        );
        trace.call(() -> {
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
                trace.outcome("ignored");
                return null;
            }
            RecordingTranscription transcription = processingAttempt.orElseThrow();
            if (!workspaceAvailable || !recordingAvailable || !hasAvailableUpload(executionContext.recordingId())) {
                transcription.fail(
                        executionContext.attemptId(),
                        "RECORDING_UNAVAILABLE"
                );
                trace.outcome("failed")
                        .error("RECORDING_UNAVAILABLE");
                return null;
            }
            persistTranscript(
                    executionContext.workspaceId(),
                    executionContext.recordingId(),
                    transcription,
                    result
            );

            trace.outcome("completed");
            return null;
        });
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
    public void completeIncremental(
            long recordingId,
            AiTranscript result
    ) {
        RecordingLog.Scope trace = incrementalPersistenceTrace(recordingId);
        trace.call(() -> {
            RecordingSession session = incrementalSession(recordingId);
            if (session.getIncrementalPipelineVersion() == null && transcriptionInputs.findByRecordingId(recordingId)
                    .filter(
                            input -> input
                                    .getStatus() == com.knot.backend.recording.domain.model.InputPreparationStatus.READY
                    )
                    .isEmpty()) {
                return null;
            }
            RecordingTranscription transcription = recordingTranscriptionRepository
                    .findByRecordingSessionIdForUpdate(recordingId)
                    .orElseThrow();
            trace.attempt(transcription.getAttemptId());
            if (transcription.getStatus() != com.knot.backend.recording.domain.model.TranscriptionStatus.QUEUED) {
                return null;
            }
            if (!hasAvailableUpload(recordingId)) {
                return null;
            }
            transcription.claim(
                    UUID.randomUUID(),
                    now(),
                    Duration.ofSeconds(60)
            );
            if (result.utterances()
                    .isEmpty()) {
                transcription.fail(
                        transcription.getAttemptId(),
                        "NO_CONTENT"
                );
                trace.outcome("failed")
                        .error("NO_CONTENT");
                return null;
            }
            persistTranscript(
                    session.getWorkspaceId(),
                    recordingId,
                    transcription,
                    result
            );
            trace.outcome("completed");
            return null;
        });
    }

    @Transactional
    public void failIncremental(
            long recordingId,
            String code
    ) {
        RecordingLog.Scope trace = incrementalPersistenceTrace(recordingId);
        trace.call(() -> {
            incrementalSession(recordingId);
            recordingTranscriptionRepository.findByRecordingSessionIdForUpdate(recordingId)
                    .ifPresent(transcription -> {
                        trace.attempt(transcription.getAttemptId());
                        if (transcription
                                .getStatus() == com.knot.backend.recording.domain.model.TranscriptionStatus.QUEUED) {
                            transcription.claim(
                                    UUID.randomUUID(),
                                    now(),
                                    Duration.ofSeconds(60)
                            );
                            transcription.fail(
                                    transcription.getAttemptId(),
                                    code
                            );
                            trace.outcome("failed")
                                    .error(code);
                        }
                    });
            return null;
        });
    }

    private RecordingLog.Scope incrementalPersistenceTrace(long recordingId) {
        return RecordingLog.begin(
                "recording.transcription.persist",
                recordingSessionRepository.findWorkspaceIdById(recordingId)
                        .orElse(0L),
                recordingId,
                null,
                null
        )
                .outcome("ignored");
    }

    private RecordingSession incrementalSession(long recordingId) {
        long workspaceId = recordingSessionRepository.findWorkspaceIdById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        RecordingSession session = recordingSessionRepository.findByIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        if ((session.getIncrementalPipelineVersion() == null && transcriptionInputs.findByRecordingId(recordingId)
                .isEmpty())
                || !session.isTranscribableIn(workspaceId)
                || !workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        workspaceId,
                        session.getMemberId()
                )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
        return session;
    }

    @Transactional
    public void fail(
            RecordingTranscriptionExecution executionContext,
            String failureCode
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.persist",
                executionContext.workspaceId(),
                executionContext.recordingId(),
                null,
                executionContext.attemptId()
        );
        trace.outcome("ignored");
        trace.call(() -> {
            workspaceRepository.findIncludingDeletedByIdForUpdate(executionContext.workspaceId());
            recordingSessionRepository.findByIdForUpdate(executionContext.recordingId());
            recordingTranscriptionRepository.findByRecordingSessionIdForUpdate(executionContext.recordingId())
                    .ifPresent(value -> {
                        if (owns(
                                value,
                                executionContext
                        )) {
                            trace.outcome("failed")
                                    .error(failureCode);
                            value.fail(
                                    executionContext.attemptId(),
                                    failureCode
                            );
                        }
                    });

            return null;
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
            RecordingLog.Scope trace = RecordingLog.begin(
                    "recording.transcription.persist",
                    execution.workspaceId(),
                    execution.recordingId(),
                    null,
                    execution.attemptId()
            );
            trace.call(() -> {
                row.fail(
                        execution.attemptId(),
                        "RECORDING_UNAVAILABLE"
                );
                trace.outcome("failed")
                        .error("RECORDING_UNAVAILABLE");
                return null;
            });
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
                RecordingLog.Scope trace = RecordingLog.begin(
                        "recording.transcription.persist",
                        execution.workspaceId(),
                        execution.recordingId(),
                        null,
                        execution.attemptId()
                );
                trace.call(() -> {
                    row.fail(
                            execution.attemptId(),
                            "AI_UNAVAILABLE"
                    );
                    trace.outcome("failed")
                            .error("AI_UNAVAILABLE");
                    return null;
                });
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
        recordingSessionRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND))
                .validateBelongsTo(workspaceId);
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
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.retry_reserve",
                workspaceId,
                previous.recordingId(),
                null,
                previous.attemptId()
        );
        return trace.call(() -> {
            recordingAccess.loadWorkspaceRecordingForUpdate(
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
                trace.attempt(row.getAttemptId())
                        .outcome("accepted");
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
            trace.attempt(row.getAttemptId())
                    .outcome("accepted");
            return new RecordingTranscriptionRetryResult(
                    previous.recordingId(),
                    row.getAttemptId(),
                    row.getStatus()
            );
        });
    }

    @Transactional
    public RecordingTranscriptionRetryResult adoptUncertainResult(
            long workspaceId,
            long memberId,
            RecordingTranscriptionExecution previous,
            AiTranscript result
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.transcription.persist",
                workspaceId,
                previous.recordingId(),
                null,
                previous.attemptId()
        );
        return trace.call(() -> {
            recordingAccess.loadWorkspaceRecordingForUpdate(
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
            trace.outcome("completed");
            return new RecordingTranscriptionRetryResult(
                    previous.recordingId(),
                    row.getAttemptId(),
                    row.getStatus()
            );
        });
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
    }

    private boolean hasAvailableUpload(long recordingId) {
        return recordingAudioUploadRepository.findByRecordingId(recordingId)
                .filter(RecordingAudioUpload::isAudioAvailable)
                .isPresent();
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
