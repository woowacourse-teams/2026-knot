package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneOffset;
import lombok.Getter;

@Getter
@Entity
@Table(name = "document_generation_jobs")
public class DocumentGenerationJob {
    private static final Duration FAILURE_RETENTION = Duration.ofDays(7);
    private static final int MAX_USER_RETRIES = 3;
    private static final Duration DEFAULT_EXECUTION_LEASE = Duration.ofSeconds(150);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private long batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DocumentGenerationJobStage stage;

    @Column(updatable = false, columnDefinition = "TEXT")
    private String topic;

    @Column(name = "transcript_id", nullable = false, updatable = false)
    private long transcriptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentGenerationJobStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "user_retry_count", nullable = false)
    private int userRetryCount;

    @Column(name = "automatic_retry_count", nullable = false)
    private int automaticRetryCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "execution_deadline_at")
    private Instant executionDeadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_cause", length = 30)
    private DocumentGenerationFailureCause failureCause;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_phase", nullable = false, length = 20)
    private DocumentGenerationPhase processingPhase = DocumentGenerationPhase.WRITE_BODY;

    @Column(name = "category_attempt_count", nullable = false)
    private int categoryAttemptCount;

    @Column(name = "phase_resume_count", nullable = false)
    private int phaseResumeCount;

    @Column(name = "category_started_at")
    private Instant categoryStartedAt;

    @Column(name = "category_deadline_at")
    private Instant categoryDeadlineAt;

    @Column(name = "category_last_failure", length = 40)
    private String categoryLastFailure;

    protected DocumentGenerationJob() {}

    private DocumentGenerationJob(
            long batchId,
            long transcriptId,
            DocumentGenerationJobStage stage,
            String topic,
            Instant createdAt
    ) {
        validateBatchId(batchId);
        validateTranscriptId(transcriptId);
        validateCreatedAt(createdAt);
        this.batchId = batchId;
        this.stage = stage;
        this.topic = topic;
        this.transcriptId = transcriptId;
        this.status = DocumentGenerationJobStatus.QUEUED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.attemptCount = 1;
        this.nextAttemptAt = createdAt;
    }

    public static DocumentGenerationJob queueClassification(
            long batchId,
            long transcriptId,
            Instant createdAt
    ) {
        return new DocumentGenerationJob(
                batchId,
                transcriptId,
                DocumentGenerationJobStage.CLASSIFICATION,
                null,
                createdAt
        );
    }

    public static DocumentGenerationJob queueGeneration(
            long batchId,
            long transcriptId,
            DocumentTopic topic,
            Instant createdAt
    ) {
        validateTopic(topic);
        return new DocumentGenerationJob(
                batchId,
                transcriptId,
                DocumentGenerationJobStage.GENERATION,
                topic.value(),
                createdAt
        );
    }

    private static void validateTopic(DocumentTopic topic) {
        if (topic == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    public boolean isSucceeded() {
        return status == DocumentGenerationJobStatus.SUCCEEDED;
    }

    public void beginCategoryAssignment(
            Instant now,
            Instant deadline
    ) {
        validateGenerationStage();
        validateRunningAttemptAt(
                attemptCount,
                now
        );
        if (processingPhase != DocumentGenerationPhase.WRITE_BODY || deadline == null || !deadline.isAfter(now)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        processingPhase = DocumentGenerationPhase.ASSIGN_CATEGORY;
        categoryStartedAt = now;
        categoryDeadlineAt = deadline;
    }

    public void reserveCategoryAttempt(Instant now) {
        validateRunningAttemptAt(
                attemptCount,
                now
        );
        if (processingPhase != DocumentGenerationPhase.ASSIGN_CATEGORY || !hasCategoryTimeAt(now)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        categoryAttemptCount++;
        updatedAt = now;
    }

    public boolean hasCategoryTimeAt(Instant now) {
        return categoryDeadlineAt != null && now.isBefore(categoryDeadlineAt);
    }

    public void recordCategoryFailure(String failure) {
        categoryLastFailure = failure;
    }

    public void resumeGeneration(
            Instant now,
            Instant nextAt,
            DocumentGenerationPhase phase
    ) {
        validateGenerationStage();
        validateRunningAttempt(attemptCount);
        validateFailureTime(now);
        validateAttemptCapacity();
        validatePreparedPhase();
        if (phase == DocumentGenerationPhase.WRITE_BODY || nextAt == null || nextAt.isBefore(now)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        processingPhase = phase;
        status = DocumentGenerationJobStatus.QUEUED;
        updatedAt = now;
        attemptCount++;
        phaseResumeCount++;
        resetExecution(nextAt);
    }

    private void validatePreparedPhase() {
        if (processingPhase == DocumentGenerationPhase.WRITE_BODY || categoryStartedAt == null) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public boolean isFailed() {
        return status == DocumentGenerationJobStatus.FAILED;
    }

    public void startRunning(Instant startedAt) {
        validateFailureTime(startedAt);
        startRunning(
                startedAt,
                startedAt.plus(DEFAULT_EXECUTION_LEASE)
        );
    }

    public boolean isReadyAt(Instant now) {
        return status == DocumentGenerationJobStatus.QUEUED && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    public boolean isRetentionExpired(Instant now) {
        validateFailureTime(now);
        return status == DocumentGenerationJobStatus.FAILED && expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        return status == DocumentGenerationJobStatus.RUNNING && executionDeadlineAt != null
                && !now.isBefore(executionDeadlineAt);
    }

    public boolean canRetryAutomatically() {
        return automaticRetryCount < 1 && attemptCount < Integer.MAX_VALUE;
    }

    public void startRunning(
            Instant startedAt,
            Instant deadline
    ) {
        if (status != DocumentGenerationJobStatus.QUEUED) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        validateFailureTime(startedAt);
        if (!isReadyAt(startedAt) || deadline == null || !deadline.isAfter(startedAt)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        validateFailureYear(deadline);
        status = DocumentGenerationJobStatus.RUNNING;
        updatedAt = startedAt;
        executionDeadlineAt = deadline;
        nextAttemptAt = null;
    }

    public void validateRunningAttemptAt(
            int expectedAttemptCount,
            Instant now
    ) {
        validateRunningAttempt(expectedAttemptCount);
        validateFailureTime(now);
        if (executionDeadlineAt == null || isExpiredAt(now)) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void validateRunningAttempt(int expectedAttemptCount) {
        validateAttempt(expectedAttemptCount);
        if (status != DocumentGenerationJobStatus.RUNNING) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void validateAttempt(int expectedAttemptCount) {
        if (attemptCount != expectedAttemptCount) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void validateClassificationStage() {
        if (stage != DocumentGenerationJobStage.CLASSIFICATION) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void validateGenerationStage() {
        if (stage != DocumentGenerationJobStage.GENERATION) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    public void recordSuccess(Instant completedAt) {
        validateRunningAttempt(attemptCount);
        validateFailureTime(completedAt);
        status = DocumentGenerationJobStatus.SUCCEEDED;
        updatedAt = completedAt;
        executionDeadlineAt = null;
        nextAttemptAt = null;
        failureCause = null;
    }

    private void validateBatchId(long batchId) {
        if (batchId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    public void retryByUser(Instant acceptedAt) {
        validateRetryState();
        validateUserRetryLimit();
        validateRetryTime(acceptedAt);
        validateRetryDeadline(acceptedAt);
        validateAttemptCapacity();
        status = DocumentGenerationJobStatus.QUEUED;
        updatedAt = acceptedAt;
        attemptCount++;
        userRetryCount++;
        resetExecution(acceptedAt);
    }

    public void retryAutomatically(
            Instant acceptedAt,
            Instant nextAt
    ) {
        validateRetryState();
        validateRetryTime(acceptedAt);
        validateRetryDeadline(acceptedAt);
        if (!canRetryAutomatically() || nextAt == null || nextAt.isBefore(acceptedAt)) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
        validateFailureYear(nextAt);
        status = DocumentGenerationJobStatus.QUEUED;
        updatedAt = acceptedAt;
        attemptCount++;
        automaticRetryCount++;
        resetExecution(nextAt);
    }

    private void resetExecution(Instant nextAt) {
        nextAttemptAt = nextAt;
        executionDeadlineAt = null;
        failureCause = null;
    }

    public void recordFailure(Instant failedAt) {
        recordFailure(
                failedAt,
                DocumentGenerationFailureCause.INTERNAL
        );
    }

    public void recordFailure(
            Instant failedAt,
            DocumentGenerationFailureCause cause
    ) {
        validateFailureState();
        validateFailureTime(failedAt);
        if (cause == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        Instant deadline = failedAt.plus(FAILURE_RETENTION);
        validateFailureYear(deadline);
        status = DocumentGenerationJobStatus.FAILED;
        updatedAt = failedAt;
        lastFailedAt = failedAt;
        expiresAt = deadline;
        nextAttemptAt = null;
        executionDeadlineAt = null;
        failureCause = cause;
    }

    private void validateFailureState() {
        if (status == DocumentGenerationJobStatus.SUCCEEDED) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateFailureTime(Instant failedAt) {
        if (failedAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        validateFailureYear(failedAt);
        if (failedAt.isBefore(updatedAt)) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateFailureYear(Instant time) {
        try {
            int year = time.atOffset(ZoneOffset.UTC)
                    .getYear();
            if (year < 1 || year > 9999) {
                throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
            }
        } catch (DateTimeException exception) {
            throw new DocumentException(
                    DocumentErrorCode.INVALID_DOCUMENT_DATA,
                    exception
            );
        }
    }

    private void validateTranscriptId(long transcriptId) {
        if (transcriptId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
    }

    private void validateCreatedAt(Instant createdAt) {
        if (createdAt == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_DATA);
        }
        validateFailureYear(createdAt);
    }

    private void validateRetryState() {
        if (status != DocumentGenerationJobStatus.FAILED) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
    }

    private void validateUserRetryLimit() {
        if (userRetryCount >= MAX_USER_RETRIES) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
    }

    private void validateRetryTime(Instant acceptedAt) {
        if (acceptedAt == null) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
        if (acceptedAt.isBefore(updatedAt)) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
        try {
            int year = acceptedAt.atOffset(ZoneOffset.UTC)
                    .getYear();
            if (year < 1 || year > 9999) {
                throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
            }
        } catch (DateTimeException exception) {
            throw new DocumentException(
                    DocumentErrorCode.RETRY_NOT_ALLOWED,
                    exception
            );
        }
    }

    private void validateRetryDeadline(Instant acceptedAt) {
        if (expiresAt == null || !acceptedAt.isBefore(expiresAt)) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
    }

    private void validateAttemptCapacity() {
        if (attemptCount == Integer.MAX_VALUE) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
    }
}
