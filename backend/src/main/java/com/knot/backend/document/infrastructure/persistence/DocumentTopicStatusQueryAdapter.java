package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.application.DocumentTopicStatusQuery;
import com.knot.backend.document.application.dto.result.DocumentTopicOperationSnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicRecoverySnapshot;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentTopicStatusQueryAdapter implements DocumentTopicStatusQuery {

    private final JdbcClient jdbc;

    @Override
    public Optional<DocumentTopicOperationSnapshot> findOperation(long workspaceId) {
        return jdbc.sql("""
                SELECT id,kind,status,retry_count,target_count,processed_count,changed_count,
                       created_at,started_at,finished_at,target_version_id
                FROM document_topic_operations WHERE workspace_id=:workspaceId
                ORDER BY CASE WHEN status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','APPLYING') THEN 0 ELSE 1 END,
                         created_at DESC,id DESC LIMIT 1
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(
                        (
                                rs,
                                row
                        ) -> operation(rs)
                )
                .optional();
    }

    @Override
    public Optional<DocumentTopicRecoverySnapshot> findRestorableVersion(long workspaceId) {
        return jdbc.sql("""
                SELECT r.id AS version_id,o.id AS operation_id,o.finished_at,o.changed_count
                FROM document_topic_recoveries r
                JOIN document_topic_operations o ON o.id=r.source_operation_id AND o.workspace_id=r.workspace_id
                WHERE r.workspace_id=:workspaceId AND o.status='SUCCEEDED' AND o.kind='REORGANIZE'
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(
                        (
                                rs,
                                row
                        ) -> new DocumentTopicRecoverySnapshot(
                                rs.getLong("version_id"),
                                rs.getLong("operation_id"),
                                instant(
                                        rs,
                                        "finished_at"
                                ),
                                rs.getLong("changed_count")
                        )
                )
                .optional();
    }

    private DocumentTopicOperationSnapshot operation(ResultSet rs) throws SQLException {
        return new DocumentTopicOperationSnapshot(
                rs.getLong("id"),
                DocumentTopicOperationKind.valueOf(rs.getString("kind")),
                DocumentTopicOperationStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                rs.getLong("target_count"),
                rs.getLong("processed_count"),
                rs.getLong("changed_count"),
                instant(
                        rs,
                        "created_at"
                ),
                instant(
                        rs,
                        "started_at"
                ),
                instant(
                        rs,
                        "finished_at"
                ),
                rs.getObject(
                        "target_version_id",
                        Long.class
                )
        );
    }

    private Instant instant(
            ResultSet rs,
            String column
    ) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}
