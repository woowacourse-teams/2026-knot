package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentTopicRestorationStore;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationSource;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationTarget;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicAssignment;
import com.knot.backend.document.domain.model.DocumentGroupingScheme;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentTopicRestorationStoreAdapter implements DocumentTopicRestorationStore {
    private static final String TARGET_SELECT = "SELECT * FROM document_topic_restoration_targets WHERE operation_id=?";

    private final JdbcTemplate jdbc;

    @Override
    public Optional<DocumentTopicRestorationSource> findSourceForUpdate(long workspaceId) {
        List<DocumentTopicRestorationSource> sources = jdbc.query(
                """
                        SELECT r.id,r.source_operation_id,o.changed_count,o.kind,o.status
                        FROM document_topic_recoveries r
                        JOIN document_topic_operations o ON o.id=r.source_operation_id AND o.workspace_id=r.workspace_id
                        WHERE r.workspace_id=? FOR UPDATE OF r
                        """,
                (
                        rs,
                        index
                ) -> source(rs),
                workspaceId
        );
        return sources.stream()
                .findFirst();
    }

    private DocumentTopicRestorationSource source(ResultSet rs) throws SQLException {
        if (!"REORGANIZE".equals(rs.getString("kind")) || !"SUCCEEDED".equals(rs.getString("status"))
                || rs.getInt("changed_count") <= 0) {
            throw invalid();
        }
        return new DocumentTopicRestorationSource(
                rs.getLong("id"),
                rs.getLong("source_operation_id"),
                rs.getInt("changed_count")
        );
    }

    @Override
    public int capture(
            long operationId,
            long workspaceId,
            long sourceOperationId
    ) {
        return jdbc.update(
                """
                        INSERT INTO document_topic_restoration_targets(operation_id,workspace_id,document_id,
                            expected_category_id,expected_grouping_scheme,expected_assignment_source,expected_rule_version,
                            restored_category_id,restored_grouping_scheme,restored_assignment_source,restored_rule_version)
                        SELECT ?,d.workspace_id,d.id,d.category_id,d.grouping_scheme,d.assignment_source,d.assignment_rule_version,
                            t.before_category_id,t.before_grouping_scheme,t.before_assignment_source,t.before_rule_version
                        FROM document_topic_operation_targets t
                        JOIN documents d ON d.id=t.document_id AND d.workspace_id=t.workspace_id
                        WHERE t.operation_id=? AND t.workspace_id=? AND t.changed ORDER BY d.id
                        """,
                operationId,
                sourceOperationId,
                workspaceId
        );
    }

    @Override
    public List<DocumentTopicRestorationTarget> pending(
            long operationId,
            int limit
    ) {
        return jdbc.query(
                TARGET_SELECT + " AND NOT prepared ORDER BY document_id LIMIT ?",
                (
                        rs,
                        index
                ) -> target(rs),
                operationId,
                limit
        );
    }

    @Override
    public List<DocumentTopicRestorationTarget> targets(long operationId) {
        return jdbc.query(
                TARGET_SELECT + " ORDER BY document_id",
                (
                        rs,
                        index
                ) -> target(rs),
                operationId
        );
    }

    private DocumentTopicRestorationTarget target(ResultSet rs) throws SQLException {
        return new DocumentTopicRestorationTarget(
                rs.getLong("document_id"),
                assignment(
                        rs,
                        "expected"
                ),
                assignment(
                        rs,
                        "restored"
                ),
                rs.getBoolean("prepared")
        );
    }

    private DocumentTopicAssignment assignment(
            ResultSet rs,
            String prefix
    ) throws SQLException {
        String source = rs.getString(prefix + "_assignment_source");
        DocumentCategoryAssignmentSource assignmentSource = null;
        if (source != null) {
            assignmentSource = DocumentCategoryAssignmentSource.valueOf(source);
        }
        return new DocumentTopicAssignment(
                DocumentGroupingScheme.valueOf(rs.getString(prefix + "_grouping_scheme")),
                rs.getObject(
                        prefix + "_category_id",
                        Long.class
                ),
                assignmentSource,
                rs.getString(prefix + "_rule_version")
        );
    }

    @Override
    public int markPrepared(
            long operationId,
            long documentId
    ) {
        return jdbc.update(
                "UPDATE document_topic_restoration_targets SET prepared=TRUE WHERE operation_id=? AND document_id=? AND NOT prepared",
                operationId,
                documentId
        );
    }

    @Override
    public void consumeRecovery(
            long workspaceId,
            long versionId,
            long sourceOperationId
    ) {
        int deleted = jdbc.update(
                "DELETE FROM document_topic_recoveries WHERE workspace_id=? AND id=? AND source_operation_id=?",
                workspaceId,
                versionId,
                sourceOperationId
        );
        if (deleted != 1) {
            throw invalid();
        }
    }

    @Override
    public void restoreCategoryAvailability(
            long workspaceId,
            long operationId,
            long sourceOperationId
    ) {
        jdbc.update(
                """
                        UPDATE document_categories c SET active_for_assignment=TRUE
                        WHERE c.workspace_id=? AND c.kind='NORMAL' AND EXISTS (
                            SELECT 1 FROM document_topic_operation_categories s
                            WHERE s.operation_id=? AND s.existing_category_id=c.id AND s.was_candidate)
                        """,
                workspaceId,
                sourceOperationId
        );
        jdbc.update(
                """
                        UPDATE document_categories c SET active_for_assignment=FALSE
                        WHERE c.workspace_id=? AND c.kind='NORMAL' AND EXISTS (
                            SELECT 1 FROM document_topic_operation_categories s
                            WHERE s.operation_id=? AND s.existing_category_id=c.id AND s.selected)
                        AND NOT EXISTS (SELECT 1 FROM document_topic_operation_categories s
                            WHERE s.operation_id=? AND s.existing_category_id=c.id AND s.was_candidate)
                        AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.workspace_id=c.workspace_id AND d.category_id=c.id
                            AND NOT EXISTS (SELECT 1 FROM document_topic_restoration_targets t
                                WHERE t.operation_id=? AND t.document_id=d.id))
                        """,
                workspaceId,
                sourceOperationId,
                sourceOperationId,
                operationId
        );
    }

    private DocumentException invalid() {
        return new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA);
    }
}
