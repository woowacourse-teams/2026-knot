package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.global.config.DocumentGenerationWorkerProperties;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentGenerationLeaseService {
    private final WorkspaceRepository workspaces;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationWorkerProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renew(DocumentGenerationExecution execution) {
        boolean deleted = workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .map(Workspace::isDeleted)
                .orElse(true);
        if (deleted) {
            return false;
        }
        Optional<DocumentGenerationJob> candidate = jobs.findByWorkspaceIdAndIdForUpdate(
                execution.workspaceId(),
                execution.jobId()
        );
        return candidate.map(
                job -> renewJob(
                        job,
                        execution
                )
        )
                .orElse(false);
    }

    private boolean renewJob(
            DocumentGenerationJob job,
            DocumentGenerationExecution execution
    ) {
        Instant now = clock.instant();
        try {
            job.renewExecutionLease(
                    execution.attemptCount(),
                    now,
                    now.plus(properties.getExecutionLease())
            );
            jobs.flush();
            return true;
        } catch (DocumentException exception) {
            return false;
        }
    }
}
