package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentGenerationPhase;
import com.knot.backend.document.domain.model.PreparedDocument;
import com.knot.backend.document.domain.repository.PreparedDocumentRepository;
import com.knot.backend.document.domain.model.DocumentConfirmation;
import com.knot.backend.document.domain.repository.DocumentConfirmationRepository;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.repository.DocumentRepository;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentGenerationResultService {

    private final WorkspaceRepository workspaces;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationInputQuery inputs;
    private final DocumentGenerationBatchRepository batches;
    private final WorkspaceMemberRepository members;
    private final DocumentRepository documents;
    private final DocumentConfirmationRepository confirmations;
    private final Clock clock;
    private final PreparedDocumentRepository prepared;
    private final DocumentCategoryCatalogService categories;
    private final ApplicationEventPublisher events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long completeGeneration(
            long workspaceId,
            long jobId,
            int expectedAttemptCount,
            DocumentGenerationResult result
    ) {
        lockWorkspace(workspaceId);
        DocumentGenerationJob job = lockGenerationJob(
                workspaceId,
                jobId,
                expectedAttemptCount
        );
        if (job.isSucceeded()) {
            return findCompletedDocument(
                    workspaceId,
                    job
            ).getId();
        }
        job.validateRunningAttempt(expectedAttemptCount);
        validateBodyPhase(job);
        DocumentGenerationInputResult input = lockRegisteredInput(
                workspaceId,
                job
        );
        validateResult(result);
        Instant completedAt = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        job.validateRunningAttemptAt(
                expectedAttemptCount,
                completedAt
        );
        Document draft = createDocument(
                workspaceId,
                job,
                input,
                result,
                completedAt
        );
        return persist(
                draft,
                job,
                completedAt
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Long> completePreparedGeneration(
            long workspaceId,
            long jobId,
            int expectedAttemptCount
    ) {
        lockWorkspace(workspaceId);
        DocumentGenerationJob job = lockGenerationJob(
                workspaceId,
                jobId,
                expectedAttemptCount
        );
        if (job.isSucceeded()) {
            Document completed = findCompletedDocument(
                    workspaceId,
                    job
            );
            return Optional.of(completed.getId());
        }
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        job.validateRunningAttemptAt(
                expectedAttemptCount,
                now
        );
        if (job.getProcessingPhase() != DocumentGenerationPhase.PUBLISH) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        DocumentGenerationInputResult input = lockRegisteredInput(
                workspaceId,
                job
        );
        PreparedDocument body = prepared.findByJobId(jobId)
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        Optional<DocumentCategory> category = categories.resolveForPublication(
                job,
                body,
                now
        );
        if (category.isEmpty()) {
            resumeAssignment(
                    job,
                    now
            );
            return Optional.empty();
        }
        DocumentGenerationResult result = new DocumentGenerationResult(
                body.getTitle(),
                body.getSummary(),
                body.getContent()
        );
        Document draft = createDocument(
                workspaceId,
                job,
                input,
                result,
                now
        );
        var decision = body.getDecision();
        draft.assignCategory(
                category.orElseThrow(() -> new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE)),
                decision.getSource(),
                body.getRuleVersion()
        );
        long documentId = persist(
                draft,
                job,
                now
        );
        prepared.delete(body);
        jobs.flush();
        events.publishEvent(
                new DocumentCategoryPublished(
                        workspaceId,
                        jobId,
                        documentId,
                        decision.getSource(),
                        body.getRuleVersion(),
                        job.getCategoryLastFailure(),
                        job.getCategoryAttemptCount(),
                        java.time.Duration.between(
                                job.getCategoryStartedAt(),
                                now
                        )
                )
        );
        return Optional.of(documentId);
    }

    private void validateBodyPhase(DocumentGenerationJob job) {
        if (job.getProcessingPhase() != DocumentGenerationPhase.WRITE_BODY) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
    }

    private void resumeAssignment(
            DocumentGenerationJob job,
            Instant now
    ) {
        DocumentGenerationBatch batch = batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        job.recordCategoryFailure("CATALOG_CHANGED");
        job.resumeGeneration(
                now,
                now,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
        batch.recordJobTransition(
                job.getStage(),
                DocumentGenerationJobStatus.RUNNING,
                job.getStatus(),
                now
        );
        jobs.flush();
    }

    private long persist(
            Document draft,
            DocumentGenerationJob job,
            Instant completedAt
    ) {
        List<Long> targets = findConfirmationTargets(draft.getWorkspaceId());
        Document document = documents.save(draft);
        saveConfirmationTargets(
                document.getId(),
                targets
        );
        job.recordSuccess(completedAt);
        DocumentGenerationBatch batch = batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        batch.recordJobTransition(
                job.getStage(),
                DocumentGenerationJobStatus.RUNNING,
                job.getStatus(),
                completedAt
        );
        jobs.flush();
        return document.getId();
    }

    private Document createDocument(
            long workspaceId,
            DocumentGenerationJob job,
            DocumentGenerationInputResult input,
            DocumentGenerationResult result,
            Instant completedAt
    ) {
        try {
            return Document.createDraft(
                    workspaceId,
                    input.recordingSessionId(),
                    input.transcriptId(),
                    job.getId(),
                    job.getTopic(),
                    result.title(),
                    result.summary(),
                    result.content(),
                    completedAt
            );
        } catch (DocumentException exception) {
            throw new DocumentException(
                    DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE,
                    exception
            );
        }
    }

    private void lockWorkspace(long workspaceId) {
        validateIdentifier(workspaceId);
        workspaces.findIncludingDeletedByIdForUpdate(workspaceId)
                .filter(workspace -> !workspace.isDeleted())
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    private Document findCompletedDocument(
            long workspaceId,
            DocumentGenerationJob job
    ) {
        Document document = documents.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        document.validateGeneratedBy(
                job,
                workspaceId
        );
        return document;
    }

    private DocumentGenerationJob lockGenerationJob(
            long workspaceId,
            long jobId,
            int expectedAttemptCount
    ) {
        validateIdentifier(jobId);
        DocumentGenerationJob job = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.DOCUMENT_GENERATION_JOB_NOT_FOUND));
        job.validateGenerationStage();
        job.validateAttempt(expectedAttemptCount);
        return job;
    }

    private DocumentGenerationInputResult lockRegisteredInput(
            long workspaceId,
            DocumentGenerationJob job
    ) {
        DocumentGenerationInputResult input = inputs.findForUpdate(
                workspaceId,
                job.getTranscriptId()
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.TRANSCRIPT_NOT_FOUND));
        if (DocumentText.isBlank(input.content())) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_INPUT);
        }
        DocumentGenerationBatch batch = batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        batch.validateGenerationTarget(
                job,
                input.recordingSessionId(),
                input.transcriptId()
        );
        return input;
    }

    private List<Long> findConfirmationTargets(long workspaceId) {
        List<Long> targets = members.findActiveMemberIdsByWorkspaceId(workspaceId);
        if (targets.isEmpty()) {
            throw new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT);
        }
        return targets;
    }

    private void saveConfirmationTargets(
            long documentId,
            List<Long> targets
    ) {
        confirmations.saveAll(
                targets.stream()
                        .map(
                                memberId -> DocumentConfirmation.require(
                                        documentId,
                                        memberId
                                )
                        )
                        .toList()
        );
    }

    private void validateResult(DocumentGenerationResult result) {
        if (result == null) {
            throw new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
        }
    }

    private void validateIdentifier(long identifier) {
        if (identifier <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
    }
}
