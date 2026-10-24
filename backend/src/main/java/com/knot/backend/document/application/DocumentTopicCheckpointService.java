package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
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
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicCheckpointService {

    private final WorkspaceRepository workspaces;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicStore store;
    private final DocumentTopicReorganizationProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DocumentTopicExecution> claim(
            long workspaceId,
            long id
    ) {
        Optional<Workspace> workspace = workspaces.findIncludingDeletedByIdForUpdate(workspaceId);
        if (workspace.isEmpty()) {
            return Optional.empty();
        }
        DocumentTopicOperation operation = requireOperation(
                workspaceId,
                id
        );
        Instant now = clock.instant();
        if (workspace.orElseThrow(this::conflict)
                .isDeleted()) {
            operation.fail(
                    "WORKSPACE_DELETED",
                    now
            );
            operations.save(operation);
            store.prune(workspaceId);
            return Optional.empty();
        }
        operation.recover(now);
        if (!operation.isActive()) {
            operations.save(operation);
            store.prune(workspaceId);
            return Optional.empty();
        }
        if (operation.canReclaimApplying(now) && operation.getKind() == DocumentTopicOperationKind.REORGANIZE) {
            return reclaimApplying(
                    operation,
                    now
            );
        }
        if (!operation.isReady(now) || operation.getKind() != DocumentTopicOperationKind.REORGANIZE) {
            return Optional.empty();
        }
        UUID token = operation.claim(now);
        List<DocumentTopicTarget> pending = store.pending(
                id,
                properties.getBatchSize()
        );
        boolean consolidate = operation.getProcessedCount() == operation.getTargetCount()
                && !operation.isConsolidated();
        List<DocumentTopicCandidate> candidates = store.categories(id);
        if (!consolidate) {
            candidates = candidates.stream()
                    .filter(DocumentTopicCandidate::wasCandidate)
                    .toList();
        }
        if (operation.getTargetCount() == 0) {
            candidates = List.of();
        }
        return Optional.of(
                new DocumentTopicExecution(
                        workspaceId,
                        id,
                        token,
                        operation.getDeadlineAt(),
                        consolidate,
                        pending,
                        candidates
                )
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean complete(
            DocumentTopicExecution execution,
            DocumentTopicPlan plan
    ) {
        DocumentTopicOperation operation = lock(execution);
        if (operation.isCancellationPendingOrCompleted()) {
            return false;
        }
        Instant now = clock.instant();
        operation.validateClaim(
                execution.token(),
                now
        );
        Set<String> categoryKeys = execution.categories()
                .stream()
                .map(DocumentTopicCandidate::key)
                .collect(Collectors.toSet());
        if (execution.consolidate()) {
            plan.validateMapping(
                    categoryKeys,
                    categoryKeys
            );
            store.consolidate(
                    operation.getId(),
                    plan
            );
            operation.checkpoint(
                    execution.token(),
                    operation.getProcessedCount(),
                    true,
                    now
            );
            return true;
        }
        Set<Long> targetIds = execution.targets()
                .stream()
                .map(DocumentTopicTarget::documentId)
                .collect(Collectors.toSet());
        plan.validateAssignments(
                targetIds,
                categoryKeys
        );
        store.saveBatch(
                operation.getId(),
                operation.getWorkspaceId(),
                plan
        );
        operation.checkpoint(
                execution.token(),
                operation.getProcessedCount() + targetIds.size(),
                false,
                now
        );
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean isClaimCurrent(DocumentTopicExecution execution) {
        DocumentTopicOperation operation = lock(execution);
        return operation.owns(
                execution.token(),
                clock.instant()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean beginApply(DocumentTopicExecution execution) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (operation.ownsApplying(
                execution.token(),
                now
        )) {
            return true;
        }
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return false;
        }
        operation.beginApplying(
                execution.token(),
                now
        );
        operations.save(operation);
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(
            DocumentTopicExecution execution,
            String code,
            boolean retryable
    ) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        operation.expire(now);
        if (!operation.isActive()) {
            operations.save(operation);
            store.prune(operation.getWorkspaceId());
            return;
        }
        if (operation.ownsApplying(
                execution.token(),
                now
        )) {
            failApplying(
                    operation,
                    execution,
                    code,
                    retryable,
                    now
            );
            operations.save(operation);
            store.prune(operation.getWorkspaceId());
            return;
        }
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return;
        }
        if (retryable) {
            operation.retry(
                    execution.token(),
                    now,
                    code
            );
        }
        if (!retryable) {
            operation.fail(
                    code,
                    now
            );
        }
        operations.save(operation);
        store.prune(operation.getWorkspaceId());
    }

    private void failApplying(
            DocumentTopicOperation operation,
            DocumentTopicExecution execution,
            String code,
            boolean retryable,
            Instant now
    ) {
        if (retryable) {
            operation.deferApplyingRetry(
                    execution.token(),
                    now,
                    code
            );
            return;
        }
        operation.fail(
                code,
                now
        );
    }

    private Optional<DocumentTopicExecution> reclaimApplying(
            DocumentTopicOperation operation,
            Instant now
    ) {
        UUID token = operation.reclaimApplying(now);
        if (token == null) {
            operations.save(operation);
            store.prune(operation.getWorkspaceId());
            return Optional.empty();
        }
        return Optional.of(
                new DocumentTopicExecution(
                        operation.getWorkspaceId(),
                        operation.getId(),
                        token,
                        operation.getDeadlineAt(),
                        false,
                        List.of(),
                        List.of()
                )
        );
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
