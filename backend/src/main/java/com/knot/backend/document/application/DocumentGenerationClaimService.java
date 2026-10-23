package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJobStage;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.model.DocumentTopicRegistrationState;
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
public class DocumentGenerationClaimService {

    private final WorkspaceRepository workspaces;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationInputQuery inputs;
    private final DocumentGenerationBatchRepository batches;
    private final DocumentGenerationWorkerProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DocumentGenerationExecution> claim(
            long workspaceId,
            long jobId
    ) {
        Optional<Workspace> workspace = workspaces.findIncludingDeletedByIdForUpdate(workspaceId);
        return workspace.flatMap(
                current -> claimInWorkspace(
                        current,
                        workspaceId,
                        jobId
                )
        );
    }

    private Optional<DocumentGenerationExecution> claimInWorkspace(
            Workspace workspace,
            long workspaceId,
            long jobId
    ) {
        Optional<DocumentGenerationJob> candidate = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        );
        return candidate.flatMap(
                job -> claimReadyJob(
                        workspace,
                        workspaceId,
                        job
                )
        );
    }

    private Optional<DocumentGenerationExecution> claimReadyJob(
            Workspace workspace,
            long workspaceId,
            DocumentGenerationJob job
    ) {
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        if (!job.isReadyAt(now)) {
            return Optional.empty();
        }
        if (workspace.isDeleted()) {
            finishInvalidInput(
                    job,
                    DocumentGenerationFailureCause.WORKSPACE_DELETED,
                    now
            );
            return Optional.empty();
        }
        Optional<DocumentGenerationInputResult> input = inputs.findForUpdate(
                workspaceId,
                job.getTranscriptId()
        );
        DocumentGenerationBatch batch = findRequiredBatch(job);
        return input.flatMap(
                stored -> startWithUsableInput(
                        workspaceId,
                        job,
                        batch,
                        stored,
                        now
                )
        )
                .or(
                        () -> finishMissingInput(
                                job,
                                now
                        )
                );
    }

    private Optional<DocumentGenerationExecution> finishMissingInput(
            DocumentGenerationJob job,
            Instant now
    ) {
        finishInvalidInput(
                job,
                DocumentGenerationFailureCause.INVALID_INPUT,
                now
        );
        return Optional.empty();
    }

    private Optional<DocumentGenerationExecution> startWithUsableInput(
            long workspaceId,
            DocumentGenerationJob job,
            DocumentGenerationBatch batch,
            DocumentGenerationInputResult input,
            Instant now
    ) {
        if (!hasUsableInput(
                job,
                batch,
                input
        )) {
            return Optional.empty();
        }
        job.startRunning(
                now,
                executionDeadline(
                        job,
                        now
                )
        );
        batch.recordJobTransition(
                job.getStage(),
                DocumentGenerationJobStatus.QUEUED,
                job.getStatus(),
                now
        );
        jobs.flush();
        return Optional.of(
                new DocumentGenerationExecution(
                        workspaceId,
                        job.getId(),
                        job.getAttemptCount(),
                        job.getStage(),
                        input.content(),
                        job.getTopic(),
                        job.getProcessingPhase()
                )
        );
    }

    private Instant executionDeadline(
            DocumentGenerationJob job,
            Instant now
    ) {
        Instant deadline = now.plus(properties.getExecutionLease());
        if (job.getProcessingPhase() != com.knot.backend.document.domain.model.DocumentGenerationPhase.ASSIGN_CATEGORY) {
            return deadline;
        }
        Instant categoryDeadline = job.getCategoryDeadlineAt();
        if (categoryDeadline != null && categoryDeadline.isAfter(now) && categoryDeadline.isBefore(deadline)) {
            return categoryDeadline;
        }
        return deadline;
    }

    private DocumentGenerationBatch findRequiredBatch(DocumentGenerationJob job) {
        return batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
    }

    private boolean hasUsableInput(
            DocumentGenerationJob job,
            DocumentGenerationBatch batch,
            DocumentGenerationInputResult stored
    ) {
        if (!hasInputText(stored)) {
            return false;
        }
        if (!matchesRegisteredInput(
                batch,
                stored
        )) {
            return false;
        }
        if (job.getStage() == DocumentGenerationJobStage.CLASSIFICATION) {
            return batch.getTopicRegistrationState() == DocumentTopicRegistrationState.WAITING_CLASSIFICATION;
        }
        if (batch.getTopicRegistrationState() != DocumentTopicRegistrationState.TOPICS_REGISTERED) {
            return false;
        }
        String topic = job.getTopic();
        return batch.getTopics()
                .contains(topic);
    }

    private boolean hasInputText(DocumentGenerationInputResult stored) {
        return !DocumentText.isBlank(stored.content());
    }

    private boolean matchesRegisteredInput(
            DocumentGenerationBatch batch,
            DocumentGenerationInputResult stored
    ) {
        Long transcriptId = stored.transcriptId();
        Long batchTranscriptId = batch.getTranscriptId();
        if (!transcriptId.equals(batchTranscriptId)) {
            return false;
        }
        long recordingId = stored.recordingSessionId();
        return recordingId == batch.getRecordingSessionId();
    }

    private void finishInvalidInput(
            DocumentGenerationJob job,
            DocumentGenerationFailureCause cause,
            Instant now
    ) {
        DocumentGenerationBatch batch = findRequiredBatch(job);
        DocumentGenerationJobStatus previous = job.getStatus();
        job.recordFailure(
                now,
                cause
        );
        batch.recordJobTransition(
                job.getStage(),
                previous,
                job.getStatus(),
                now
        );
        jobs.flush();
    }
}
