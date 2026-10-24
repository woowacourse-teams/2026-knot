package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentTopicStore;
import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentTopicStoreAdapter implements DocumentTopicStore {
    private static final String TARGET_SELECT = """
            SELECT t.document_id,d.topic,d.title,d.summary,substring(d.content,1,2000) evidence,
              t.before_category_id,t.before_grouping_scheme,t.before_assignment_source,t.before_rule_version,t.after_key
            FROM document_topic_operation_targets t JOIN documents d ON d.id=t.document_id AND d.workspace_id=t.workspace_id
            WHERE t.operation_id=?
            """;

    private final JdbcTemplate jdbc;

    @Override
    public int capture(
            long operationId,
            long workspaceId
    ) {
        jdbc.update(
                """
                        INSERT INTO document_topic_operation_categories(operation_id,workspace_id,category_key,existing_category_id,name,description,was_candidate)
                        SELECT ?,workspace_id,'e:'||id,id,name,description,TRUE FROM document_categories
                        WHERE workspace_id=? AND kind='NORMAL' AND active_for_assignment
                        """,
                operationId,
                workspaceId
        );
        return jdbc.update(
                """
                        INSERT INTO document_topic_operation_targets(operation_id,workspace_id,document_id,before_category_id,
                          before_grouping_scheme,before_assignment_source,before_rule_version)
                        SELECT ?,workspace_id,id,category_id,grouping_scheme,assignment_source,assignment_rule_version
                        FROM documents WHERE workspace_id=? ORDER BY id
                        """,
                operationId,
                workspaceId
        );
    }

    @Override
    public List<DocumentTopicTarget> pending(
            long operationId,
            int limit
    ) {
        return readTargets(
                TARGET_SELECT + " AND t.after_key IS NULL ORDER BY t.document_id LIMIT ?",
                operationId,
                limit
        );
    }

    @Override
    public List<DocumentTopicTarget> targets(long operationId) {
        return readTargets(
                TARGET_SELECT + " ORDER BY t.document_id",
                operationId
        );
    }

    private List<DocumentTopicTarget> readTargets(
            String sql,
            Object... args
    ) {
        return jdbc.query(
                sql,
                (
                        row,
                        index
                ) -> new DocumentTopicTarget(
                        row.getLong("document_id"),
                        row.getString("topic"),
                        row.getString("title"),
                        row.getString("summary"),
                        row.getString("evidence"),
                        row.getObject(
                                "before_category_id",
                                Long.class
                        ),
                        row.getString("before_grouping_scheme"),
                        row.getString("before_assignment_source"),
                        row.getString("before_rule_version"),
                        row.getString("after_key")
                ),
                args
        );
    }

    @Override
    public List<DocumentTopicCandidate> categories(long operationId) {
        return jdbc.query(
                """
                        SELECT category_key,existing_category_id,name,description,was_candidate FROM document_topic_operation_categories
                        WHERE operation_id=? ORDER BY category_key
                        """,
                (
                        row,
                        index
                ) -> new DocumentTopicCandidate(
                        row.getString("category_key"),
                        row.getObject(
                                "existing_category_id",
                                Long.class
                        ),
                        row.getString("name"),
                        row.getString("description"),
                        row.getBoolean("was_candidate")
                ),
                operationId
        );
    }

    @Override
    public void saveBatch(
            long operationId,
            long workspaceId,
            DocumentTopicPlan plan
    ) {
        for (Map.Entry<String, String> candidate : plan.getNewNames()
                .entrySet()) {
            jdbc.update(
                    """
                            INSERT INTO document_topic_operation_categories(operation_id,workspace_id,category_key,name,description)
                            VALUES (?,?,?,?,?)
                            """,
                    operationId,
                    workspaceId,
                    candidate.getKey(),
                    candidate.getValue(),
                    plan.getNewDescriptions()
                            .get(candidate.getKey())
            );
        }
        for (Map.Entry<Long, String> assignment : plan.getAssignments()
                .entrySet()) {
            jdbc.update(
                    "UPDATE document_topic_operation_targets SET after_key=? WHERE operation_id=? AND document_id=? AND after_key IS NULL",
                    assignment.getValue(),
                    operationId,
                    assignment.getKey()
            );
        }
    }

    @Override
    public void consolidate(
            long operationId,
            DocumentTopicPlan plan
    ) {
        if (plan.getCategoryMapping()
                .isEmpty()) {
            return;
        }
        List<Object> parameters = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        for (Map.Entry<String, String> mapping : plan.getCategoryMapping()
                .entrySet()) {
            placeholders.add("(CAST(? AS text),CAST(? AS text))");
            parameters.add(mapping.getKey());
            parameters.add(mapping.getValue());
        }
        parameters.add(operationId);
        jdbc.update(
                """
                        UPDATE document_topic_operation_targets t SET after_key=m.target_key
                        FROM (VALUES %s) AS m(source_key,target_key) WHERE t.operation_id=? AND t.after_key=m.source_key
                        """.formatted(
                        String.join(
                                ",",
                                placeholders
                        )
                ),
                parameters.toArray()
        );
    }

    @Override
    public void resolveCategory(
            long operationId,
            String key,
            long categoryId
    ) {
        jdbc.update(
                "UPDATE document_topic_operation_categories SET existing_category_id=?,selected=TRUE WHERE operation_id=? AND category_key=?",
                categoryId,
                operationId,
                key
        );
    }

    @Override
    public void markChanged(
            long operationId,
            long documentId
    ) {
        jdbc.update(
                "UPDATE document_topic_operation_targets SET changed=TRUE WHERE operation_id=? AND document_id=?",
                operationId,
                documentId
        );
    }

    @Override
    public void replaceRecovery(
            long workspaceId,
            long operationId,
            Instant now
    ) {
        jdbc.update(
                """
                        INSERT INTO document_topic_recoveries(workspace_id,version_id,source_operation_id,created_at) VALUES (?,?,?,?)
                        ON CONFLICT(workspace_id) DO UPDATE SET version_id=EXCLUDED.version_id,source_operation_id=EXCLUDED.source_operation_id,created_at=EXCLUDED.created_at
                        """,
                workspaceId,
                UUID.randomUUID(),
                operationId,
                Timestamp.from(now)
        );
    }

    @Override
    public void prune(long workspaceId) {
        jdbc.update(
                """
                        DELETE FROM document_topic_operation_targets t USING document_topic_operations o
                        WHERE t.operation_id=o.id AND o.workspace_id=? AND o.status IN ('SUCCEEDED','FAILED','CANCELLED')
                        AND NOT EXISTS (SELECT 1 FROM document_topic_recoveries r WHERE r.source_operation_id=o.id)
                        """,
                workspaceId
        );
        jdbc.update(
                """
                        DELETE FROM document_topic_operation_categories c USING document_topic_operations o
                        WHERE c.operation_id=o.id AND o.workspace_id=? AND o.status IN ('SUCCEEDED','FAILED','CANCELLED')
                        AND NOT EXISTS (SELECT 1 FROM document_topic_recoveries r WHERE r.source_operation_id=o.id)
                        """,
                workspaceId
        );
    }
}
