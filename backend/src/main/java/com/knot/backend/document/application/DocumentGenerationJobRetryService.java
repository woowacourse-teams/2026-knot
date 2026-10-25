package com.knot.backend.document.application;

import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationJobRetryResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentGenerationJobRetryService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationInputQuery inputs;
    private final DocumentGenerationBatchRepository batches;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentGenerationJobRetryResult retry(
            long workspaceId,
            long memberId,
            long jobId
    ) {
        validateWorkspaceId(workspaceId);
        validateMemberId(memberId);
        validateJobId(jobId);
        lockWorkspaceAndValidateAccess(
                workspaceId,
                memberId
        );
        DocumentGenerationJob job = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.DOCUMENT_GENERATION_JOB_NOT_FOUND));
        DocumentGenerationInputResult input = inputs.findForUpdate(
                workspaceId,
                job.getTranscriptId()
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED));
        validateInput(input);
        Instant acceptedAt = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        DocumentGenerationBatch batch = batches.findByIdForUpdate(job.getBatchId())
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT));
        job.retryByUser(acceptedAt);
        batch.recordJobTransition(
                job.getStage(),
                DocumentGenerationJobStatus.FAILED,
                job.getStatus(),
                acceptedAt
        );
        jobs.flush();
        return new DocumentGenerationJobRetryResult(
                jobId,
                job.getStatus(),
                job.getAttemptCount()
        );
    }

    private void lockWorkspaceAndValidateAccess(
            long workspaceId,
            long memberId
    ) {
        workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
        if (!members.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    private void validateInput(DocumentGenerationInputResult input) {
        if (DocumentText.isBlank(input.content())) {
            throw new DocumentException(DocumentErrorCode.RETRY_NOT_ALLOWED);
        }
    }

    private void validateWorkspaceId(long workspaceId) {
        if (workspaceId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
    }

    private void validateMemberId(long memberId) {
        if (memberId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
    }

    private void validateJobId(long jobId) {
        if (jobId <= 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_PARAMETER);
        }
    }
}
