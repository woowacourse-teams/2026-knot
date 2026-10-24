package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationTarget;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.global.config.DocumentTopicReorganizationProperties;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicRestorationCheckpointService {

    private final WorkspaceRepository workspaces;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicRestorationStore restorations;
    private final DocumentTopicStore store;
    private final DocumentTopicReorganizationProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DocumentTopicRestorationExecution> claim(
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
        if (operation.getKind() != DocumentTopicOperationKind.RESTORE) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (workspace.orElseThrow(this::invalid)
                .isDeleted()) {
            operation.fail(
                    "WORKSPACE_DELETED",
                    now
            );
            cleanup(operation);
            return Optional.empty();
        }
        operation.recover(now);
        if (!operation.isActive()) {
            cleanup(operation);
            return Optional.empty();
        }
        if (operation.canReclaimApplying(now)) {
            return reclaim(
                    operation,
                    now
            );
        }
        if (!operation.isReady(now)) {
            return Optional.empty();
        }
        UUID token = operation.claim(now);
        return Optional.of(
                execution(
                        operation,
                        token,
                        operation.isConsolidated()
                )
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean prepare(DocumentTopicRestorationExecution execution) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        if (!operation.owns(
                execution.token(),
                now
        )) {
            return false;
        }
        List<DocumentTopicRestorationTarget> targets = restorations.pending(
                operation.getId(),
                properties.getBatchSize()
        );
        if (targets.isEmpty() && operation.getProcessedCount() != operation.getTargetCount()) {
            throw invalid();
        }
        int prepared = 0;
        for (DocumentTopicRestorationTarget target : targets) {
            prepared += restorations.markPrepared(
                    operation.getId(),
                    target.documentId()
            );
        }
        if (prepared != targets.size()) {
            throw invalid();
        }
        int processed = operation.getProcessedCount() + prepared;
        operation.checkpoint(
                execution.token(),
                processed,
                processed == operation.getTargetCount(),
                clock.instant()
        );
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean beginApply(DocumentTopicRestorationExecution execution) {
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
            DocumentTopicRestorationExecution execution,
            String code,
            boolean retryable
    ) {
        DocumentTopicOperation operation = lock(execution);
        Instant now = clock.instant();
        operation.expire(now);
        if (!operation.isActive()) {
            cleanup(operation);
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
            cleanup(operation);
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
        cleanup(operation);
    }

    private Optional<DocumentTopicRestorationExecution> reclaim(
            DocumentTopicOperation operation,
            Instant now
    ) {
        UUID token = operation.reclaimApplying(now);
        if (token == null) {
            cleanup(operation);
            return Optional.empty();
        }
        return Optional.of(
                execution(
                        operation,
                        token,
                        true
                )
        );
    }

    private void failApplying(
            DocumentTopicOperation operation,
            DocumentTopicRestorationExecution execution,
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

    private DocumentTopicRestorationExecution execution(
            DocumentTopicOperation operation,
            UUID token,
            boolean ready
    ) {
        return new DocumentTopicRestorationExecution(
                operation.getWorkspaceId(),
                operation.getId(),
                token,
                ready
        );
    }

    private DocumentTopicOperation lock(DocumentTopicRestorationExecution execution) {
        Workspace workspace = workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .orElseThrow(this::invalid);
        DocumentTopicOperation operation = requireOperation(
                execution.workspaceId(),
                execution.operationId()
        );
        if (operation.getKind() != DocumentTopicOperationKind.RESTORE) {
            throw invalid();
        }
        if (workspace.isDeleted()) {
            operation.fail(
                    "WORKSPACE_DELETED",
                    clock.instant()
            );
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
                .orElseThrow(this::invalid);
    }

    private void cleanup(DocumentTopicOperation operation) {
        operations.save(operation);
        store.prune(operation.getWorkspaceId());
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA);
    }
}
