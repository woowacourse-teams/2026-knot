package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentCategoryRepository;
import com.knot.backend.document.domain.repository.DocumentRepository;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicApplyService {
    public static final String RULE_VERSION = "reorganization-v1";

    private final WorkspaceRepository workspaces;
    private final DocumentTopicOperationRepository operations;
    private final DocumentRepository documents;
    private final DocumentCategoryRepository categories;
    private final DocumentTopicStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(DocumentTopicExecution execution) {
        Workspace workspace = workspaces.findIncludingDeletedByIdForUpdate(execution.workspaceId())
                .orElseThrow(this::invalid);
        DocumentTopicOperation operation = operations.lock(
                execution.workspaceId(),
                execution.operationId()
        )
                .orElseThrow(this::invalid);
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
        limitStatements(operation);
        List<DocumentTopicTarget> targets = store.targets(operation.getId());
        requireComplete(
                operation,
                targets
        );
        Map<String, DocumentTopicCandidate> candidates = store.categories(operation.getId())
                .stream()
                .collect(
                        Collectors.toMap(
                                DocumentTopicCandidate::key,
                                c -> c
                        )
                );
        Set<String> used = targets.stream()
                .map(DocumentTopicTarget::afterKey)
                .collect(Collectors.toSet());
        Map<String, DocumentCategory> resolved = resolve(
                execution.workspaceId(),
                used,
                candidates
        );
        int changed = reassign(
                operation,
                targets,
                resolved
        );
        if (changed > 0) {
            for (Map.Entry<String, DocumentCategory> resolvedCategory : resolved.entrySet()) {
                store.resolveCategory(
                        operation.getId(),
                        resolvedCategory.getKey(),
                        resolvedCategory.getValue()
                                .getId()
                );
            }
            updateCandidates(
                    operation.getWorkspaceId(),
                    candidates,
                    resolved
            );
            store.replaceRecovery(
                    operation.getWorkspaceId(),
                    operation.getId(),
                    clock.instant()
            );
        }
        documents.flush();
        operation.succeed(
                execution.token(),
                changed,
                clock.instant()
        );
        operations.save(operation);
        store.prune(operation.getWorkspaceId());
    }

    private void requireComplete(
            DocumentTopicOperation operation,
            List<DocumentTopicTarget> targets
    ) {
        if (!operation.isConsolidated() || targets.size() != operation.getTargetCount()) {
            throw invalid();
        }
        if (targets.stream()
                .anyMatch(target -> target.afterKey() == null)) {
            throw invalid();
        }
    }

    private void limitStatements(DocumentTopicOperation operation) {
        long remaining = Duration.between(
                clock.instant(),
                operation.getDeadlineAt()
        )
                .toMillis();
        if (remaining <= 0) {
            throw invalid();
        }
        String timeout = Long.toString(
                Math.min(
                        remaining,
                        30000
                )
        );
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)",
                String.class,
                timeout
        );
        jdbc.queryForObject(
                "select set_config('lock_timeout', ?, true)",
                String.class,
                timeout
        );
    }

    private Map<String, DocumentCategory> resolve(
            long workspaceId,
            Set<String> used,
            Map<String, DocumentTopicCandidate> candidates
    ) {
        List<DocumentCategory> all = new ArrayList<>(categories.findAllByWorkspaceId(workspaceId));
        Map<String, DocumentCategory> result = new HashMap<>();
        for (String key : used) {
            DocumentTopicCandidate candidate = candidates.get(key);
            if (candidate == null) {
                throw invalid();
            }
            result.put(
                    key,
                    resolveCandidate(
                            workspaceId,
                            candidate,
                            all
                    )
            );
        }
        return result;
    }

    private DocumentCategory resolveCandidate(
            long workspaceId,
            DocumentTopicCandidate candidate,
            List<DocumentCategory> all
    ) {
        if (candidate.existingCategoryId() != null) {
            return all.stream()
                    .filter(
                            c -> candidate.existingCategoryId()
                                    .equals(c.getId())
                    )
                    .findFirst()
                    .orElseThrow(this::invalid);
        }
        String name = DocumentCategory.normalizeName(candidate.name());
        Optional<DocumentCategory> exact = all.stream()
                .filter(c -> name.equals(c.getNormalizedName()))
                .findFirst();
        if (exact.isPresent()) {
            return requireMatchingDescription(
                    exact.orElseThrow(this::invalid),
                    candidate
            );
        }
        DocumentCategory category = categories.save(
                DocumentCategory.create(
                        workspaceId,
                        candidate.name(),
                        candidate.description(),
                        clock.instant()
                )
        );
        all.add(category);
        return category;
    }

    private DocumentCategory requireMatchingDescription(
            DocumentCategory category,
            DocumentTopicCandidate candidate
    ) {
        String description = DocumentCategory.normalizeDescription(candidate.description());
        if (!category.getDescription()
                .equals(description)) {
            throw invalid();
        }
        return category;
    }

    private int reassign(
            DocumentTopicOperation operation,
            List<DocumentTopicTarget> targets,
            Map<String, DocumentCategory> resolved
    ) {
        int changed = 0;
        for (DocumentTopicTarget target : targets) {
            Document document = documents.findByWorkspaceIdAndIdForUpdate(
                    operation.getWorkspaceId(),
                    target.documentId()
            )
                    .orElseThrow(this::invalid);
            validateBefore(
                    document,
                    target
            );
            DocumentCategory category = resolved.get(target.afterKey());
            if (document.reassignCategory(
                    category,
                    RULE_VERSION
            )) {
                store.markChanged(
                        operation.getId(),
                        target.documentId()
                );
                changed++;
            }
        }
        return changed;
    }

    private void validateBefore(
            Document document,
            DocumentTopicTarget target
    ) {
        if (!Objects.equals(
                document.getCategoryId(),
                target.beforeCategoryId()
        ) || !document.getGroupingScheme()
                .name()
                .equals(target.beforeGroupingScheme())) {
            throw invalid();
        }
        if (!Objects.equals(
                source(document),
                target.beforeAssignmentSource()
        ) || !Objects.equals(document.getAssignmentRuleVersion(),
                target.beforeRuleVersion()
        )) {
            throw invalid();
        }
    }

    private String source(Document document) {
        if (document.getAssignmentSource() == null) {
            return null;
        }
        return document.getAssignmentSource()
                .name();
    }

    private void updateCandidates(
            long workspaceId,
            Map<String, DocumentTopicCandidate> candidates,
            Map<String, DocumentCategory> resolved
    ) {
        Set<Long> selected = resolved.values()
                .stream()
                .map(DocumentCategory::getId)
                .collect(Collectors.toSet());
        List<DocumentCategory> all = categories.findAllByWorkspaceId(workspaceId);
        Set<Long> original = candidates.values()
                .stream()
                .filter(DocumentTopicCandidate::wasCandidate)
                .map(DocumentTopicCandidate::existingCategoryId)
                .collect(Collectors.toSet());
        for (DocumentCategory category : all) {
            if (original.contains(category.getId()) || selected.contains(category.getId())) {
                category.changeAssignmentAvailability(selected.contains(category.getId()));
            }
        }
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_PLAN);
    }
}
