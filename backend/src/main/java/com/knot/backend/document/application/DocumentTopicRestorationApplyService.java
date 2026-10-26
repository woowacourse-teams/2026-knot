package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationSource;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationTarget;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentTopicAssignment;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.repository.DocumentRepository;
import com.knot.backend.document.domain.repository.DocumentCategoryRepository;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicRestorationApplyService {
    private static final long MAX_STATEMENT_MILLIS = 30000;

    private final WorkspaceRepository workspaces;
    private final DocumentTopicOperationRepository operations;
    private final DocumentRepository documents;
    private final DocumentCategoryRepository categories;
    private final DocumentTopicRestorationStore restorations;
    private final DocumentTopicStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(DocumentTopicRestorationExecution execution) {
        limitStatements(MAX_STATEMENT_MILLIS);
        Workspace workspace = workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .orElseThrow(this::invalid);
        DocumentTopicOperation operation = operations.lock(
                execution.workspaceId(),
                execution.operationId()
        )
                .orElseThrow(this::invalid);
        if (operation.getKind() != DocumentTopicOperationKind.RESTORE) {
            throw invalid();
        }
        if (workspace.isDeleted()) {
            operation.fail(
                    "WORKSPACE_DELETED",
                    clock.instant()
            );
            operations.save(operation);
            store.prune(execution.workspaceId());
            return;
        }
        operation.validateApplyingClaim(
                execution.token(),
                clock.instant()
        );
        limitStatements(
                Duration.between(
                        clock.instant(),
                        operation.getDeadlineAt()
                )
                        .toMillis()
        );
        DocumentTopicRestorationSource source = restorations.findSourceForUpdate(execution.workspaceId())
                .orElseThrow(this::invalid);
        requireSource(
                operation,
                source
        );
        List<DocumentTopicRestorationTarget> targets = restorations.targets(operation.getId());
        requireComplete(
                operation,
                targets
        );
        Map<Long, DocumentCategory> catalog = categories.findAllByWorkspaceId(execution.workspaceId())
                .stream()
                .collect(
                        Collectors.toMap(
                                DocumentCategory::getId,
                                category -> category
                        )
                );
        int changed = restore(
                operation,
                targets,
                catalog
        );
        documents.flush();
        restorations.restoreCategoryAvailability(
                execution.workspaceId(),
                operation.getId(),
                source.sourceOperationId()
        );
        restorations.consumeRecovery(
                execution.workspaceId(),
                source.versionId(),
                source.sourceOperationId()
        );
        operation.succeed(
                execution.token(),
                changed,
                clock.instant()
        );
        operations.save(operation);
        store.prune(execution.workspaceId());
    }

    private void requireSource(
            DocumentTopicOperation operation,
            DocumentTopicRestorationSource source
    ) {
        if (source.versionId() != operation.getTargetVersionId()
                || source.sourceOperationId() != operation.getRestorationSourceOperationId()
                || source.changedCount() != operation.getTargetCount()) {
            throw invalid();
        }
    }

    private void requireComplete(
            DocumentTopicOperation operation,
            List<DocumentTopicRestorationTarget> targets
    ) {
        if (targets.size() != operation.getTargetCount() || operation.getProcessedCount() != operation.getTargetCount()
                || !operation.isConsolidated()) {
            throw invalid();
        }
        if (targets.stream()
                .anyMatch(target -> !target.prepared())) {
            throw invalid();
        }
    }

    private int restore(
            DocumentTopicOperation operation,
            List<DocumentTopicRestorationTarget> targets,
            Map<Long, DocumentCategory> catalog
    ) {
        int changed = 0;
        for (DocumentTopicRestorationTarget target : targets) {
            changed += restoreDocument(
                    operation.getWorkspaceId(),
                    target,
                    catalog
            );
        }
        return changed;
    }

    private int restoreDocument(
            long workspaceId,
            DocumentTopicRestorationTarget target,
            Map<Long, DocumentCategory> catalog
    ) {
        Document document = documents.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                target.documentId()
        )
                .orElseThrow(this::invalid);
        if (!target.expected()
                .equals(document.topicAssignment())) {
            throw invalid();
        }
        DocumentTopicAssignment restored = target.restored();
        DocumentCategory category = null;
        if (restored.categoryId() != null) {
            category = catalog.get(restored.categoryId());
        }
        if (document.restoreTopicAssignment(
                restored,
                category
        )) {
            return 1;
        }
        return 0;
    }

    private void limitStatements(long remaining) {
        if (remaining <= 0) {
            throw invalid();
        }
        String timeout = Long.toString(
                Math.min(
                        remaining,
                        MAX_STATEMENT_MILLIS
                )
        );
        jdbc.queryForObject(
                "SELECT set_config('statement_timeout',?,true)",
                String.class,
                timeout
        );
        jdbc.queryForObject(
                "SELECT set_config('lock_timeout',?,true)",
                String.class,
                timeout
        );
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA);
    }
}
