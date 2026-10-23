package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import com.knot.backend.document.application.dto.result.DocumentCategoryAttempt;
import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.model.DocumentGenerationPhase;
import com.knot.backend.document.domain.model.PreparedDocument;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.repository.PreparedDocumentRepository;
import com.knot.backend.global.config.DocumentCategoryProperties;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCategoryCheckpointService {

    private final WorkspaceRepository workspaces;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationBatchRepository batches;
    private final DocumentGenerationInputQuery inputs;
    private final PreparedDocumentRepository prepared;
    private final DocumentCategoryCatalogService catalog;
    private final DocumentCategoryProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void prepare(
            DocumentGenerationExecution execution,
            DocumentGenerationResult result
    ) {
        Instant now = now();
        DocumentGenerationJob job = lock(
                execution,
                now
        );
        DocumentGenerationInputResult input = inputs.findForUpdate(
                execution.workspaceId(),
                job.getTranscriptId()
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.TRANSCRIPT_NOT_FOUND));
        DocumentGenerationBatch batch = lockBatch(job);
        batch.validateGenerationTarget(
                job,
                input.recordingSessionId(),
                input.transcriptId()
        );
        if (result == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
        }
        prepared.save(
                new PreparedDocument(
                        job.getId(),
                        execution.workspaceId(),
                        input.transcriptId(),
                        input.recordingSessionId(),
                        result.title(),
                        result.summary(),
                        result.content(),
                        now
                )
        );
        job.beginCategoryAssignment(
                now,
                now.plus(properties.getTotalBudget())
        );
        requeue(
                job,
                now,
                now,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentCategoryAttempt reserve(DocumentGenerationExecution execution) {
        Instant now = now();
        DocumentGenerationJob job = lock(
                execution,
                now
        );
        requirePhase(
                job,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
        PreparedDocument body = requirePrepared(job);
        List<DocumentCategory> candidates = catalog.normalCategories(execution.workspaceId());
        DocumentCategoryInput input = new DocumentCategoryInput(
                job.getTopic(),
                new DocumentGenerationResult(
                        body.getTitle(),
                        body.getSummary(),
                        body.getContent()
                ),
                candidates
        );
        String version = catalog.version(candidates);
        if (!catalog.canCall(
                job,
                now
        )) {
            return new DocumentCategoryAttempt(
                    input,
                    Duration.ZERO,
                    version
            );
        }
        Duration timeout = remainingTimeout(
                job,
                now
        );
        if (timeout.isZero() || timeout.isNegative()) {
            return new DocumentCategoryAttempt(
                    input,
                    Duration.ZERO,
                    version
            );
        }
        job.reserveCategoryAttempt(now);
        jobs.flush();
        return new DocumentCategoryAttempt(
                input,
                timeout,
                version
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decide(
            DocumentGenerationExecution execution,
            DocumentCategoryDecision decision,
            String version
    ) {
        Instant now = now();
        DocumentGenerationJob job = lock(
                execution,
                now
        );
        requirePhase(
                job,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
        PreparedDocument body = requirePrepared(job);
        List<DocumentCategory> candidates = catalog.normalCategories(execution.workspaceId());
        DocumentCategoryDecision checked = catalog.validateDecision(
                decision,
                candidates
        );
        String currentVersion = catalog.version(candidates);
        if (catalog.needsReview(
                checked,
                version,
                currentVersion
        )) {
            reviewChangedCatalog(
                    job,
                    body,
                    candidates,
                    now
            );
            return;
        }
        saveDecision(
                job,
                body,
                checked,
                currentVersion,
                now
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failAssignment(
            DocumentGenerationExecution execution,
            String cause,
            boolean retryable
    ) {
        Instant now = now();
        DocumentGenerationJob job = lock(
                execution,
                now
        );
        requirePhase(
                job,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
        job.recordCategoryFailure(cause);
        Instant next = now.plus(properties.getRetryBackoff());
        if (retryable && catalog.canCall(
                job,
                next
        )) {
            requeue(
                    job,
                    now,
                    next,
                    DocumentGenerationPhase.ASSIGN_CATEGORY
            );
            return;
        }
        PreparedDocument body = requirePrepared(job);
        List<DocumentCategory> candidates = catalog.normalCategories(execution.workspaceId());
        saveDecision(
                job,
                body,
                catalog.fallbackFor(
                        job,
                        body,
                        candidates
                ),
                catalog.version(candidates),
                now
        );
    }

    private void reviewChangedCatalog(
            DocumentGenerationJob job,
            PreparedDocument body,
            List<DocumentCategory> candidates,
            Instant now
    ) {
        job.recordCategoryFailure("CATALOG_CHANGED");
        if (catalog.canCall(
                job,
                now
        )) {
            requeue(
                    job,
                    now,
                    now,
                    DocumentGenerationPhase.ASSIGN_CATEGORY
            );
            return;
        }
        saveDecision(
                job,
                body,
                catalog.fallbackFor(
                        job,
                        body,
                        candidates
                ),
                catalog.version(candidates),
                now
        );
    }

    private void saveDecision(
            DocumentGenerationJob job,
            PreparedDocument body,
            DocumentCategoryDecision decision,
            String version,
            Instant now
    ) {
        body.assign(
                decision,
                version,
                DocumentCategoryFallbackClassifier.RULE_VERSION
        );
        log.info(
                "Document category assigned workspaceId={} jobId={} source={} reason={} tries={} elapsedMs={} rule={}",
                body.getWorkspaceId(),
                job.getId(),
                decision.getSource(),
                job.getCategoryLastFailure(),
                job.getCategoryAttemptCount(),
                Duration.between(
                        job.getCategoryStartedAt(),
                        now
                )
                        .toMillis(),
                DocumentCategoryFallbackClassifier.RULE_VERSION
        );
        requeue(
                job,
                now,
                now,
                DocumentGenerationPhase.PUBLISH
        );
    }

    private Duration remainingTimeout(
            DocumentGenerationJob job,
            Instant now
    ) {
        Duration remaining = Duration.between(
                now,
                job.getCategoryDeadlineAt()
        );
        Duration leaseRemaining = Duration.between(
                now,
                job.getExecutionDeadlineAt()
        )
                .minusMillis(100);
        return shorter(
                shorter(
                        properties.getRequestTimeout(),
                        remaining
                ),
                leaseRemaining
        );
    }

    private Duration shorter(
            Duration first,
            Duration second
    ) {
        if (first.compareTo(second) <= 0) {
            return first;
        }
        return second;
    }

    private DocumentGenerationJob lock(
            DocumentGenerationExecution execution,
            Instant now
    ) {
        workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .filter(workspace -> !workspace.isDeleted())
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
        DocumentGenerationJob job = jobs.findByWorkspaceIdAndIdForUpdate(
                execution.workspaceId(),
                execution.jobId()
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.DOCUMENT_GENERATION_JOB_NOT_FOUND));
        job.validateGenerationStage();
        job.validateRunningAttemptAt(
                execution.attemptCount(),
                now
        );
        return job;
    }

    private PreparedDocument requirePrepared(DocumentGenerationJob job) {
        return prepared.findByJobId(job.getId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
    }

    private void requirePhase(
            DocumentGenerationJob job,
            DocumentGenerationPhase phase
    ) {
        if (job.getProcessingPhase() != phase) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    private DocumentGenerationBatch lockBatch(DocumentGenerationJob job) {
        return batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
    }

    private void requeue(
            DocumentGenerationJob job,
            Instant now,
            Instant next,
            DocumentGenerationPhase phase
    ) {
        DocumentGenerationBatch batch = lockBatch(job);
        job.resumeGeneration(
                now,
                next,
                phase
        );
        batch.recordJobTransition(
                job.getStage(),
                DocumentGenerationJobStatus.RUNNING,
                job.getStatus(),
                now
        );
        jobs.flush();
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
