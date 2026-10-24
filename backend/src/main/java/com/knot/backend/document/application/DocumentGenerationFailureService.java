package com.knot.backend.document.application;

import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationPhase;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.DocumentGenerationRetryPolicy;
import com.knot.backend.global.config.DocumentGenerationWorkerProperties;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentGenerationFailureService {

    private final WorkspaceRepository workspaces;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationBatchRepository batches;
    private final DocumentGenerationRetryPolicy policy;
    private final DocumentGenerationWorkerProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failAttempt(
            long workspaceId,
            long jobId,
            int expectedAttemptCount,
            DocumentGenerationFailureCause cause
    ) {
        failCurrentAttempt(
                workspaceId,
                jobId,
                expectedAttemptCount,
                cause,
                false
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireAttempt(
            long workspaceId,
            long jobId,
            int expectedAttemptCount
    ) {
        failCurrentAttempt(
                workspaceId,
                jobId,
                expectedAttemptCount,
                DocumentGenerationFailureCause.EXECUTION_EXPIRED,
                true
        );
    }

    private void failCurrentAttempt(
            long workspaceId,
            long jobId,
            int expectedAttemptCount,
            DocumentGenerationFailureCause cause,
            boolean onlyExpired
    ) {
        Optional<Workspace> workspace = workspaces.findIncludingDeletedByIdForUpdate(workspaceId);
        if (workspace.isEmpty()) {
            return;
        }
        Optional<DocumentGenerationJob> candidate = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        );
        if (candidate.isEmpty()) {
            return;
        }
        DocumentGenerationJob job = candidate
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.DOCUMENT_GENERATION_JOB_NOT_FOUND));
        if (!isCurrentExecution(
                job,
                expectedAttemptCount
        )) {
            return;
        }
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        if (onlyExpired && !job.isExpiredAt(now)) {
            return;
        }
        DocumentGenerationFailureCause actualCause = resolveFailureCause(
                workspace.orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT)),
                job,
                cause,
                now
        );
        DocumentGenerationBatch batch = batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        DocumentGenerationJobStatus previous = job.getStatus();
        if (resumePrepared(
                job,
                actualCause,
                now
        )) {
            batch.recordJobTransition(
                    job.getStage(),
                    previous,
                    job.getStatus(),
                    now
            );
            jobs.flush();
            return;
        }
        job.recordFailure(
                now,
                actualCause
        );
        if (policy.isAutomaticallyRetryable(actualCause) && job.canRetryAutomatically()) {
            job.retryAutomatically(
                    now,
                    policy.calculateNextAttemptAt(
                            now,
                            properties.getAutomaticRetryBackoff()
                    )
            );
        }
        batch.recordJobTransition(
                job.getStage(),
                previous,
                job.getStatus(),
                now
        );
        jobs.flush();
    }

    private DocumentGenerationFailureCause resolveFailureCause(
            Workspace workspace,
            DocumentGenerationJob job,
            DocumentGenerationFailureCause cause,
            Instant now
    ) {
        if (workspace.isDeleted()) {
            return DocumentGenerationFailureCause.WORKSPACE_DELETED;
        }
        if (job.isExpiredAt(now)) {
            return DocumentGenerationFailureCause.EXECUTION_EXPIRED;
        }
        return cause;
    }

    private boolean resumePrepared(
            DocumentGenerationJob job,
            DocumentGenerationFailureCause cause,
            Instant now
    ) {
        if (job.getProcessingPhase() == DocumentGenerationPhase.WRITE_BODY
                || cause == DocumentGenerationFailureCause.WORKSPACE_DELETED) {
            return false;
        }
        job.recordCategoryFailure(cause.name());
        job.resumeGeneration(
                now,
                now.plus(properties.getAutomaticRetryBackoff()),
                job.getProcessingPhase()
        );
        return true;
    }

    private boolean isCurrentExecution(
            DocumentGenerationJob job,
            int expectedAttemptCount
    ) {
        return job.getStatus() == DocumentGenerationJobStatus.RUNNING && job.getAttemptCount() == expectedAttemptCount;
    }
}
