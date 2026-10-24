package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
@Entity
@Table(name = "document_topic_operations")
public class DocumentTopicOperation {
    public static final long DEADLINE_SECONDS = 1800;
    public static final long LEASE_SECONDS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID operationId;

    @Column(nullable = false, updatable = false)
    private long workspaceId;

    @Column(nullable = false, updatable = false)
    private long requesterId;

    @Column(nullable = false, updatable = false)
    private UUID requestId;

    @Column(updatable = false)
    private Long targetVersionId;

    @Column(updatable = false)
    private Long restorationSourceOperationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DocumentTopicOperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentTopicOperationStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private UUID claimToken;

    private Instant leaseUntil;

    private Instant finishedAt;

    private Instant startedAt;

    private Instant cancelRequestedAt;

    private String failureCode;

    private int retryCount;

    private int targetCount;

    private int processedCount;

    private int changedCount;

    private boolean consolidated;

    protected DocumentTopicOperation() {}

    public static DocumentTopicOperation reorganize(
            long workspaceId,
            long requesterId,
            UUID requestId,
            Instant now
    ) {
        if (workspaceId <= 0 || requesterId <= 0 || requestId == null || now == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
        DocumentTopicOperation operation = new DocumentTopicOperation();
        operation.operationId = UUID.randomUUID();
        operation.workspaceId = workspaceId;
        operation.requesterId = requesterId;
        operation.requestId = requestId;
        operation.kind = DocumentTopicOperationKind.REORGANIZE;
        operation.status = DocumentTopicOperationStatus.QUEUED;
        operation.createdAt = now;
        operation.deadlineAt = now.plusSeconds(DEADLINE_SECONDS);
        operation.nextAttemptAt = now;
        return operation;
    }

    public static DocumentTopicOperation restoration(
            long workspaceId,
            long requesterId,
            UUID requestId,
            long versionId,
            long sourceOperationId,
            Instant now
    ) {
        if (versionId <= 0 || sourceOperationId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
        DocumentTopicOperation operation = reorganize(
                workspaceId,
                requesterId,
                requestId,
                now
        );
        operation.kind = DocumentTopicOperationKind.RESTORE;
        operation.targetVersionId = versionId;
        operation.restorationSourceOperationId = sourceOperationId;
        return operation;
    }

    public void capture(int count) {
        if (count < 0 || targetCount != 0 || status != DocumentTopicOperationStatus.QUEUED) {
            throw conflict();
        }
        targetCount = count;
    }

    public boolean isReady(Instant now) {
        return status == DocumentTopicOperationStatus.QUEUED && !now.isBefore(nextAttemptAt)
                && now.isBefore(deadlineAt);
    }

    public UUID claim(Instant now) {
        if (!isReady(now)) {
            throw conflict();
        }
        status = DocumentTopicOperationStatus.RUNNING;
        recordFirstStart(now);
        claimToken = UUID.randomUUID();
        leaseUntil = now.plusSeconds(LEASE_SECONDS);
        return claimToken;
    }

    public boolean owns(
            UUID token,
            Instant now
    ) {
        return status == DocumentTopicOperationStatus.RUNNING && token != null && token.equals(claimToken)
                && now.isBefore(leaseUntil) && now.isBefore(deadlineAt);
    }

    public void validateClaim(
            UUID token,
            Instant now
    ) {
        if (!owns(
                token,
                now
        )) {
            throw conflict();
        }
    }

    public void checkpoint(
            UUID token,
            int count,
            boolean consolidated,
            Instant now
    ) {
        validateClaim(
                token,
                now
        );
        if (count < processedCount || count > targetCount) {
            throw conflict();
        }
        processedCount = count;
        this.consolidated = consolidated;
        retryCount = 0;
        requeue(now);
    }

    public void retry(
            UUID token,
            Instant now,
            String code
    ) {
        if (!owns(
                token,
                now
        )) {
            return;
        }
        if (retryCount >= 1) {
            fail(
                    code,
                    now
            );
            return;
        }
        retryCount++;
        failureCode = code;
        requeue(now.plusSeconds(5));
    }

    public void recover(Instant now) {
        expire(now);
        if (status != DocumentTopicOperationStatus.RUNNING || now.isBefore(leaseUntil)) {
            return;
        }
        if (retryCount >= 1) {
            fail(
                    "LEASE_EXHAUSTED",
                    now
            );
            return;
        }
        retryCount++;
        requeue(now);
    }

    public void expire(Instant now) {
        if (!isActive() || isCancellationPendingOrCompleted() || now.isBefore(deadlineAt)) {
            return;
        }
        fail(
                "DEADLINE_EXCEEDED",
                now
        );
    }

    public boolean isActive() {
        return status == DocumentTopicOperationStatus.QUEUED || status == DocumentTopicOperationStatus.RUNNING
                || status == DocumentTopicOperationStatus.CANCEL_REQUESTED
                || status == DocumentTopicOperationStatus.APPLYING;
    }

    public void fail(
            String code,
            Instant now
    ) {
        if (!isActive() || isCancellationPendingOrCompleted()) {
            return;
        }
        status = DocumentTopicOperationStatus.FAILED;
        failureCode = code;
        finish(now);
    }

    public void succeed(
            UUID token,
            int changed,
            Instant now
    ) {
        validateApplyingClaim(
                token,
                now
        );
        if (processedCount != targetCount || !consolidated || changed < 0 || changed > targetCount) {
            throw conflict();
        }
        changedCount = changed;
        status = DocumentTopicOperationStatus.SUCCEEDED;
        failureCode = null;
        finish(now);
    }

    public boolean isCancellationPendingOrCompleted() {
        return status == DocumentTopicOperationStatus.CANCEL_REQUESTED
                || status == DocumentTopicOperationStatus.CANCELLED;
    }

    public void requestCancellation(Instant now) {
        if (isCancellationPendingOrCompleted()) {
            return;
        }
        if (status != DocumentTopicOperationStatus.QUEUED && status != DocumentTopicOperationStatus.RUNNING) {
            throw new DocumentException(DocumentErrorCode.TOPIC_OPERATION_NOT_CANCELLABLE);
        }
        status = DocumentTopicOperationStatus.CANCEL_REQUESTED;
        cancelRequestedAt = now;
        claimToken = null;
        leaseUntil = null;
        failureCode = null;
    }

    public void completeCancellation(Instant now) {
        if (status != DocumentTopicOperationStatus.CANCEL_REQUESTED) {
            return;
        }
        status = DocumentTopicOperationStatus.CANCELLED;
        failureCode = null;
        finish(now);
    }

    public void beginApplying(
            UUID token,
            Instant now
    ) {
        if (ownsApplying(
                token,
                now
        )) {
            return;
        }
        validateClaim(
                token,
                now
        );
        if (processedCount != targetCount || !consolidated) {
            throw conflict();
        }
        status = DocumentTopicOperationStatus.APPLYING;
        nextAttemptAt = now;
        leaseUntil = now.plusSeconds(LEASE_SECONDS);
    }

    public boolean ownsApplying(
            UUID token,
            Instant now
    ) {
        return status == DocumentTopicOperationStatus.APPLYING && token != null && token.equals(claimToken)
                && now.isBefore(leaseUntil) && now.isBefore(deadlineAt);
    }

    public void validateApplyingClaim(
            UUID token,
            Instant now
    ) {
        if (!ownsApplying(
                token,
                now
        )) {
            throw conflict();
        }
    }

    public boolean canReclaimApplying(Instant now) {
        return status == DocumentTopicOperationStatus.APPLYING && !now.isBefore(leaseUntil)
                && !now.isBefore(nextAttemptAt) && now.isBefore(deadlineAt);
    }

    public UUID reclaimApplying(Instant now) {
        if (!canReclaimApplying(now)) {
            throw conflict();
        }
        if (retryCount >= 1) {
            fail(
                    "LEASE_EXHAUSTED",
                    now
            );
            return null;
        }
        retryCount++;
        claimToken = UUID.randomUUID();
        leaseUntil = now.plusSeconds(LEASE_SECONDS);
        return claimToken;
    }

    public void deferApplyingRetry(
            UUID token,
            Instant now,
            String code
    ) {
        if (!ownsApplying(
                token,
                now
        )) {
            return;
        }
        if (retryCount >= 1) {
            fail(
                    code,
                    now
            );
            return;
        }
        failureCode = code;
        leaseUntil = now;
        nextAttemptAt = now.plusSeconds(5);
    }

    private void requeue(Instant at) {
        status = DocumentTopicOperationStatus.QUEUED;
        nextAttemptAt = at;
        claimToken = null;
        leaseUntil = null;
    }

    private void recordFirstStart(Instant now) {
        if (startedAt == null) {
            startedAt = now;
        }
    }

    private void finish(Instant now) {
        finishedAt = now;
        claimToken = null;
        leaseUntil = null;
    }

    private DocumentException conflict() {
        return new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT);
    }
}
