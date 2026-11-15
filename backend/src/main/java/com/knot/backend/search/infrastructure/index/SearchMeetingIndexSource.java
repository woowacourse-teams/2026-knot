package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

final class SearchMeetingIndexSource {
    private static final String SELECT_DOCUMENTS = """
            SELECT d.id, concat_ws(E'\n\n', d.title, d.topic, d.summary, d.content) AS searchable_text
            FROM documents d
            JOIN recording_sessions r ON r.id = d.recording_session_id AND r.workspace_id = d.workspace_id
            WHERE d.workspace_id = :workspace AND d.status IN ('DRAFT', 'ARCHIVED')
            """;

    private final JdbcClient jdbc;

    SearchMeetingIndexSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Map<Long, String> documents(long workspaceId) {
        return documents(
                jdbc.sql(SELECT_DOCUMENTS + " ORDER BY d.id")
                        .param(
                                "workspace",
                                workspaceId
                        )
        );
    }

    Map<Long, String> eligibleDocuments(
            SearchToolScope scope,
            SearchMeetingSearchItem item
    ) {
        SearchDateFilter date = item.meetingDate();
        String sql = SELECT_DOCUMENTS + " AND d.created_at <= :cutoff";
        if (item.documentId() != null) {
            sql += " AND d.id = :document";
        }
        if (date != null) {
            sql += " AND r.started_at >= :from AND r.started_at < :before";
        }
        JdbcClient.StatementSpec statement = jdbc.sql(sql + " ORDER BY d.id")
                .param(
                        "workspace",
                        scope.workspaceId()
                )
                .param(
                        "cutoff",
                        Timestamp.from(scope.startedAt())
                );
        if (item.documentId() != null) {
            statement.param(
                    "document",
                    item.documentId()
            );
        }
        if (date != null) {
            statement.param(
                    "from",
                    Timestamp.from(date.from())
            )
                    .param(
                            "before",
                            Timestamp.from(date.before())
                    );
        }
        return documents(statement);
    }

    private Map<Long, String> documents(JdbcClient.StatementSpec statement) {
        return statement.query(result -> {
            Map<Long, String> documents = new LinkedHashMap<>();
            while (result.next()) {
                documents.put(
                        result.getLong("id"),
                        result.getString("searchable_text")
                );
            }
            return documents;
        });
    }
}
