package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.dto.result.SearchDocumentRecord;
import com.knot.backend.search.application.dto.query.SearchDocumentCursor;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchDocumentQueryAdapter implements SearchDocumentQuery {
    private static final String SELECT = """
            SELECT d.id, d.title, d.topic, d.content, d.status, d.created_at, d.archived_at, r.started_at, 0 AS score
            FROM documents d JOIN recording_sessions r ON r.id = d.recording_session_id
            WHERE d.workspace_id = :workspace AND r.workspace_id = :workspace
              AND d.status IN ('DRAFT', 'ARCHIVED') AND d.created_at <= :cutoff
              AND (CAST(:topic AS text) IS NULL OR d.topic = :topic)
              AND (CAST(:from AS timestamptz) IS NULL OR r.started_at >= :from)
              AND (CAST(:before AS timestamptz) IS NULL OR r.started_at < :before)
            """;

    private final JdbcClient jdbc;

    public java.util.Optional<SearchDocumentRecord> find(
            SearchToolScope scope,
            long documentId
    ) {
        String sql = """
                SELECT d.id, d.title, d.topic, d.content, d.status, d.created_at, d.archived_at, r.started_at, 0 AS score
                FROM documents d JOIN recording_sessions r ON r.id = d.recording_session_id
                WHERE d.id = :id AND d.workspace_id = :workspace AND r.workspace_id = :workspace
                  AND d.status IN ('DRAFT', 'ARCHIVED') AND d.created_at <= :cutoff
                """;
        return jdbc.sql(sql)
                .param(
                        "id",
                        documentId
                )
                .param(
                        "workspace",
                        scope.workspaceId()
                )
                .param(
                        "cutoff",
                        Timestamp.from(scope.requestAcceptedAt())
                )
                .query(this::map)
                .optional();
    }

    public List<SearchDocumentRecord> searchPage(
            SearchToolScope scope,
            SearchDocumentParameters parameters,
            SearchDocumentCursor cursor
    ) {
        List<String> terms = java.util.Arrays.stream(
                parameters.query()
                        .strip()
                        .split("\\s+")
        )
                .distinct()
                .limit(8)
                .toList();
        String score = scoreExpression(terms.size());
        String sql = "SELECT * FROM (" + SELECT.replace(
                "0 AS score",
                score + " AS score"
        ) + ") candidates WHERE score > 0";
        if (cursor != null) {
            sql += " AND (score, started_at, id) < (:lastScore, :lastTime, :lastId)";
        }
        sql += " ORDER BY score DESC, started_at DESC, id DESC LIMIT :limit";
        JdbcClient.StatementSpec statement = filters(
                jdbc.sql(sql),
                scope,
                parameters
        );
        for (int index = 0; index < terms.size(); index++) {
            statement.param(
                    "term" + index,
                    "%" + escape(terms.get(index)) + "%"
            );
        }
        if (cursor != null) {
            statement.param(
                    "lastScore",
                    cursor.score()
            )
                    .param(
                            "lastTime",
                            Timestamp.from(cursor.recordedAt())
                    )
                    .param(
                            "lastId",
                            cursor.documentId()
                    );
        }
        return statement.query(this::map)
                .list();
    }

    private String scoreExpression(int count) {
        List<String> scores = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            String match = " ILIKE :term" + index + " ESCAPE '!'";
            scores.add(
                    "(CASE WHEN d.title" + match + " THEN 8 ELSE 0 END" + " + CASE WHEN d.topic" + match
                            + " THEN 4 ELSE 0 END" + " + CASE WHEN d.summary" + match + " THEN 2 ELSE 0 END"
                            + " + CASE WHEN d.content" + match + " THEN 1 ELSE 0 END)"
            );
        }
        return String.join(
                " + ",
                scores
        );
    }

    private String escape(String value) {
        return value.replace(
                "!",
                "!!"
        )
                .replace(
                        "%",
                        "!%"
                )
                .replace(
                        "_",
                        "!_"
                );
    }

    public List<SearchDocumentRecord> listPage(
            SearchToolScope scope,
            SearchDocumentParameters parameters,
            SearchDocumentCursor cursor
    ) {
        String sql = SELECT;
        if (cursor != null) {
            sql += " AND (r.started_at, d.id) < (:lastTime, :lastId)";
        }
        sql += " ORDER BY r.started_at DESC, d.id DESC LIMIT :limit";
        JdbcClient.StatementSpec statement = filters(
                jdbc.sql(sql),
                scope,
                parameters
        );
        if (cursor != null) {
            statement.param(
                    "lastTime",
                    Timestamp.from(cursor.recordedAt())
            )
                    .param(
                            "lastId",
                            cursor.documentId()
                    );
        }
        return statement.query(this::map)
                .list();
    }

    private JdbcClient.StatementSpec filters(
            JdbcClient.StatementSpec statement,
            SearchToolScope scope,
            SearchDocumentParameters parameters
    ) {
        return statement.param(
                "workspace",
                scope.workspaceId()
        )
                .param(
                        "cutoff",
                        Timestamp.from(scope.requestAcceptedAt())
                )
                .param(
                        "topic",
                        parameters.topic()
                )
                .param(
                        "from",
                        timestamp(parameters.recordedFrom())
                )
                .param(
                        "before",
                        timestamp(parameters.recordedBefore())
                )
                .param(
                        "limit",
                        parameters.size() + 1
                );
    }

    private Timestamp timestamp(Instant value) {
        if (value == null) {
            return null;
        }
        return Timestamp.from(value);
    }

    private SearchDocumentRecord map(
            ResultSet row,
            int index
    ) throws SQLException {
        return new SearchDocumentRecord(
                row.getLong("id"),
                row.getString("title"),
                row.getString("topic"),
                row.getString("content"),
                DocumentStatus.valueOf(row.getString("status")),
                row.getTimestamp("started_at")
                        .toInstant(),
                row.getTimestamp("created_at")
                        .toInstant(),
                archivedAt(row),
                row.getInt("score")
        );
    }

    private Instant archivedAt(ResultSet row) throws SQLException {
        Timestamp value = row.getTimestamp("archived_at");
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
