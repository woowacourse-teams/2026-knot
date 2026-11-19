package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.application.dto.result.SearchDocumentSelection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchDocumentListStore {

    private final JdbcClient jdbc;

    public SearchDocumentListPosition create(
            SearchToolScope scope,
            boolean sortByCreation,
            String query,
            Instant from,
            Instant before
    ) {
        long listId = jdbc
                .sql(
                        """
                                INSERT INTO search_document_lists
                                    (conversation_id, first_answer_id, cutoff_at, query_text, sort_by_creation, recorded_from, recorded_before)
                                VALUES (:conversation, :answer, :cutoff, :query, :sort, :from, :before)
                                RETURNING id
                                """
                )
                .param(
                        "conversation",
                        scope.conversationId()
                )
                .param(
                        "answer",
                        scope.answerId()
                )
                .param(
                        "cutoff",
                        timestamp(scope.requestAcceptedAt())
                )
                .param(
                        "query",
                        query
                )
                .param(
                        "sort",
                        sortByCreation
                )
                .param(
                        "from",
                        timestamp(from)
                )
                .param(
                        "before",
                        timestamp(before)
                )
                .query(Long.class)
                .single();

        int count = jdbc.sql("""
                INSERT INTO search_document_list_items (list_id, ordinal, document_id)
                SELECT l.id, row_number() OVER (
                    ORDER BY CASE WHEN l.sort_by_creation THEN d.created_at ELSE r.started_at END DESC, d.id DESC
                ), d.id
                FROM search_document_lists l
                JOIN search_conversations c ON c.id = l.conversation_id
                JOIN documents d ON d.workspace_id = c.workspace_id
                JOIN recording_sessions r ON r.id = d.recording_session_id AND r.workspace_id = c.workspace_id
                WHERE l.id = :list AND d.status IN ('DRAFT', 'ARCHIVED') AND d.created_at <= l.cutoff_at
                  AND (l.recorded_from IS NULL OR r.started_at >= l.recorded_from)
                  AND (l.recorded_before IS NULL OR r.started_at < l.recorded_before)
                  AND (l.query_text = '' OR d.title ILIKE :pattern ESCAPE '!'
                    OR d.topic ILIKE :pattern ESCAPE '!' OR d.content ILIKE :pattern ESCAPE '!')
                """)
                .param(
                        "list",
                        listId
                )
                .param(
                        "pattern",
                        "%" + query.replace(
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
                                ) + "%"
                )
                .update();

        jdbc.sql("UPDATE search_document_lists SET item_count = :count WHERE id = :list")
                .param(
                        "count",
                        count
                )
                .param(
                        "list",
                        listId
                )
                .update();
        return new SearchDocumentListPosition(
                listId,
                0,
                count
        );
    }

    public Optional<SearchDocumentListPosition> latestForUpdate(long conversationId) {
        return jdbc.sql("""
                SELECT id, displayed_through, item_count FROM search_document_lists
                WHERE conversation_id = :conversation ORDER BY first_answer_id DESC LIMIT 1 FOR UPDATE
                """)
                .param(
                        "conversation",
                        conversationId
                )
                .query(
                        (
                                row,
                                index
                        ) -> new SearchDocumentListPosition(
                                row.getLong("id"),
                                row.getInt("displayed_through"),
                                row.getInt("item_count")
                        )
                )
                .optional();
    }

    public void savePage(
            long answerId,
            long listId,
            int first,
            int last
    ) {
        jdbc.sql("""
                INSERT INTO search_document_list_answers (answer_id, list_id, first_ordinal, last_ordinal, selection)
                VALUES (:answer, :list, :first, :last, false)
                """)
                .param(
                        "answer",
                        answerId
                )
                .param(
                        "list",
                        listId
                )
                .param(
                        "first",
                        first
                )
                .param(
                        "last",
                        last
                )
                .update();
        jdbc.sql("UPDATE search_document_lists SET displayed_through = :last WHERE id = :list")
                .param(
                        "last",
                        last
                )
                .param(
                        "list",
                        listId
                )
                .update();
    }

    public boolean saveSelection(
            long answerId,
            long listId,
            int ordinal
    ) {
        return jdbc.sql("""
                INSERT INTO search_document_list_answers (answer_id, list_id, first_ordinal, last_ordinal, selection)
                SELECT :answer, l.id, i.ordinal, i.ordinal, true
                FROM search_document_list_items i
                JOIN search_document_lists l ON l.id = i.list_id
                JOIN search_conversations c ON c.id = l.conversation_id
                JOIN documents d ON d.id = i.document_id AND d.workspace_id = c.workspace_id
                JOIN recording_sessions r ON r.id = d.recording_session_id AND r.workspace_id = c.workspace_id
                WHERE l.id = :list AND i.ordinal = :ordinal AND i.ordinal <= l.displayed_through
                    AND d.status IN ('DRAFT', 'ARCHIVED')
                """)
                .param(
                        "answer",
                        answerId
                )
                .param(
                        "list",
                        listId
                )
                .param(
                        "ordinal",
                        ordinal
                )
                .update() == 1;
    }

    public Map<Long, SearchDocumentListResult> findAnswers(
            long workspaceId,
            long memberId,
            long conversationId,
            List<Long> answerIds
    ) {
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(
                        """
                                SELECT a.answer_id, a.selection, a.last_ordinal < l.item_count AS has_more,
                                       i.ordinal, d.id AS document_id, d.title
                                FROM search_document_list_answers a
                                JOIN search_document_lists l ON l.id = a.list_id
                                JOIN search_conversations c ON c.id = l.conversation_id
                                JOIN search_messages m ON m.id = a.answer_id AND m.conversation_id = c.id
                                    AND m.role = 'ASSISTANT' AND m.status = 'COMPLETED'
                                JOIN search_document_list_items i ON i.list_id = l.id AND i.ordinal BETWEEN a.first_ordinal AND a.last_ordinal
                                LEFT JOIN documents d ON d.id = i.document_id AND d.workspace_id = c.workspace_id
                                    AND d.status IN ('DRAFT', 'ARCHIVED')
                                    AND EXISTS (
                                        SELECT 1 FROM recording_sessions r
                                        WHERE r.id = d.recording_session_id AND r.workspace_id = c.workspace_id
                                    )
                                WHERE c.id = :conversation AND c.workspace_id = :workspace AND c.member_id = :member
                                    AND a.answer_id IN (:answers)
                                ORDER BY a.answer_id, i.ordinal
                                """
                )
                .param(
                        "conversation",
                        conversationId
                )
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        memberId
                )
                .param(
                        "answers",
                        answerIds
                )
                .query(rows -> {
                    Map<Long, SearchDocumentListResult> results = new HashMap<>();
                    while (rows.next()) {
                        long answerId = rows.getLong("answer_id");
                        SearchDocumentListResult result = results.get(answerId);
                        if (result == null) {
                            result = new SearchDocumentListResult(
                                    rows.getBoolean("selection"),
                                    rows.getBoolean("has_more"),
                                    new ArrayList<>()
                            );
                            results.put(
                                    answerId,
                                    result
                            );
                        }
                        long documentId = rows.getLong("document_id");
                        if (!rows.wasNull()) {
                            result.items()
                                    .add(
                                            new SearchDocumentSelection(
                                                    documentId,
                                                    rows.getString("title"),
                                                    rows.getInt("ordinal")
                                            )
                                    );
                        }
                    }
                    return results;
                });
    }

    private Timestamp timestamp(Instant value) {
        if (value == null) {
            return null;
        }
        return Timestamp.from(value);
    }
}
