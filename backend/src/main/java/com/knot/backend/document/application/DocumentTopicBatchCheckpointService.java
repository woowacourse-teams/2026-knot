package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.global.config.DocumentTopicReorganizationProperties;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicBatchCheckpointService {

    private final WorkspaceRepository workspaces;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicStore store;
    private final DocumentTopicReorganizationProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renew(DocumentTopicExecution execution) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        operation.expire(now);
        if (!operation.owns(
                execution.token(),
                now
        )) {
            operations.save(operation);
            store.prune(execution.workspaceId());
            return false;
        }
        operation.renewClaim(
                execution.token(),
                now
        );
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DocumentTopicExecution> nextBatch(
            DocumentTopicExecution execution,
            Set<Long> excluded
    ) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return Optional.empty();
        }
        List<DocumentTopicTarget> targets = store.readyPending(
                execution.operationId(),
                properties.getBatchSize(),
                excluded,
                now
        );
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new DocumentTopicExecution(
                        execution.workspaceId(),
                        execution.operationId(),
                        execution.token(),
                        execution.deadline(),
                        false,
                        targets,
                        execution.categories()
                )
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean completeBatch(
            DocumentTopicExecution execution,
            DocumentTopicPlan plan
    ) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return false;
        }
        Set<Long> ids = targetIds(execution);
        if (!store.allPending(
                execution.operationId(),
                ids
        )) {
            return false;
        }
        Set<String> keys = execution.categories()
                .stream()
                .map(DocumentTopicCandidate::key)
                .collect(Collectors.toSet());
        plan.validateAssignments(
                ids,
                keys
        );
        if (!plan.getCategoryMapping()
                .isEmpty()) {
            throw new DocumentException(DocumentErrorCode.INVALID_TOPIC_PLAN);
        }
        DocumentTopicTarget first = execution.targets()
                .getFirst();
        DocumentTopicPlan scoped = plan.scopeNewKeys(first.documentId());
        store.saveBatch(
                execution.operationId(),
                execution.workspaceId(),
                scoped
        );
        operation.recordBatchProgress(
                execution.token(),
                operation.getProcessedCount() + ids.size(),
                now
        );
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean retryBatch(
            DocumentTopicExecution execution,
            String code
    ) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return false;
        }
        Set<Long> ids = targetIds(execution);
        if (!store.allPending(
                execution.operationId(),
                ids
        )) {
            return false;
        }
        if (!store.retryBatch(
                execution.operationId(),
                ids,
                now.plusSeconds(5)
        )) {
            operation.fail(
                    code,
                    now
            );
            operations.save(operation);
            store.prune(execution.workspaceId());
            return false;
        }
        operation.renewClaim(
                execution.token(),
                now
        );
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean finishBatches(DocumentTopicExecution execution) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return false;
        }
        if (operation.getProcessedCount() == operation.getTargetCount()) {
            operation.checkpoint(
                    execution.token(),
                    operation.getProcessedCount(),
                    false,
                    now
            );
            return true;
        }
        operation.deferBatches(
                execution.token(),
                store.nextBatchAttempt(execution.operationId()),
                now
        );
        return true;
    }

    private Set<Long> targetIds(DocumentTopicExecution execution) {
        return execution.targets()
                .stream()
                .map(DocumentTopicTarget::documentId)
                .collect(Collectors.toSet());
    }

    private DocumentTopicOperation lock(DocumentTopicExecution execution) {
        Workspace workspace = workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .orElseThrow(this::conflict);
        DocumentTopicOperation operation = requireOperation(
                execution.workspaceId(),
                execution.operationId()
        );
        if (workspace.isDeleted()) {
            operation.fail(
                    "WORKSPACE_DELETED",
                    clock.instant()
            );
            operations.save(operation);
            store.prune(execution.workspaceId());
        }
        return operation;
    }

    private DocumentTopicOperation requireOperation(
            long workspaceId,
            long id
    ) {
        return operations.lock(
                workspaceId,
                id
        )
                .orElseThrow(this::conflict);
    }

    private DocumentException conflict() {
        return new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT);
    }
}
