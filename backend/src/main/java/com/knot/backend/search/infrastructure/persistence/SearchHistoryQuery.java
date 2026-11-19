package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchToolScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchHistoryQuery {

    private final JdbcClient jdbc;

    public List<Map<String, Object>> beforeQuestion(SearchToolScope scope) {
        List<Map<String, Object>> rows = jdbc
                .sql(
                        """
                                SELECT m.role, m.content FROM search_messages m
                                JOIN search_conversations c ON c.id = m.conversation_id
                                WHERE c.id = :conversation AND c.workspace_id = :workspace AND c.member_id = :member
                                  AND EXISTS (SELECT 1 FROM workspace_members w
                                      WHERE w.workspace_id = c.workspace_id AND w.member_id = c.member_id)
                                  AND NOT EXISTS (SELECT 1 FROM search_hidden_answer_bodies h WHERE h.answer_id = m.id)
                                  AND m.sequence < (SELECT sequence FROM search_messages WHERE id = :question AND conversation_id = c.id)
                                  AND (m.status IN ('COMPLETED', 'STOPPED') OR
                                    (m.role = 'USER' AND EXISTS (
                                      SELECT 1 FROM search_messages a WHERE a.conversation_id = c.id
                                        AND a.sequence = m.sequence + 1 AND a.role = 'ASSISTANT'
                                        AND a.status IN ('COMPLETED', 'STOPPED')
                                    )))
                                ORDER BY m.sequence DESC LIMIT 20
                                """
                )
                .param(
                        "conversation",
                        scope.conversationId()
                )
                .param(
                        "workspace",
                        scope.workspaceId()
                )
                .param(
                        "member",
                        scope.memberId()
                )
                .param(
                        "question",
                        scope.questionId()
                )
                .query(
                        (
                                row,
                                index
                        ) -> Map.<String, Object>of(
                                "role",
                                row.getString("role")
                                        .toLowerCase(java.util.Locale.ROOT),
                                "content",
                                row.getString("content")
                        )
                )
                .list();
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }
}
