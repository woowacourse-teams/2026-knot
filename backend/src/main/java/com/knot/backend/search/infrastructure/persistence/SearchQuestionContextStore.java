package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.query.SearchQuestionContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchQuestionContextStore {
    private final JdbcClient jdbc;

    public Optional<SearchQuestionContext> findPreviousEvidenceContext(SearchToolScope scope) {
        return jdbc.sql("""
                SELECT COALESCE(ctx.search_text, prior_question.content) AS search_text,
                       CASE WHEN COUNT(*) = COUNT(r.id) THEN ctx.recorded_from END AS recorded_from,
                       CASE WHEN COUNT(*) = COUNT(r.id) THEN ctx.recorded_before END AS recorded_before,
                       CASE WHEN COUNT(*) = COUNT(r.id) AND COUNT(DISTINCT r.id) = 1 THEN MIN(r.id) END AS recording_id
                FROM search_messages current_question
                JOIN search_conversations c ON c.id = current_question.conversation_id
                JOIN workspace_members wm ON wm.workspace_id = c.workspace_id AND wm.member_id = c.member_id
                JOIN search_messages prior_answer ON prior_answer.conversation_id = c.id
                     AND prior_answer.sequence = current_question.sequence - 1
                     AND prior_answer.role = 'ASSISTANT' AND prior_answer.status = 'COMPLETED'
                     AND prior_answer.failure_code IS NULL
                JOIN search_messages prior_question ON prior_question.conversation_id = c.id
                     AND prior_question.sequence = prior_answer.sequence - 1 AND prior_question.role = 'USER'
                JOIN search_evidences e ON e.message_id = prior_answer.id
                LEFT JOIN documents d ON d.id = e.live_document_id AND d.workspace_id = c.workspace_id
                     AND d.status IN ('DRAFT', 'ARCHIVED') AND d.created_at <= :cutoff
                LEFT JOIN recording_sessions r ON r.id = d.recording_session_id AND r.workspace_id = c.workspace_id
                LEFT JOIN search_question_contexts ctx ON ctx.question_message_id = prior_question.id
                WHERE c.id = :conversation AND c.workspace_id = :workspace AND c.member_id = :member
                     AND current_question.id = :question AND current_question.role = 'USER'
                GROUP BY prior_question.content, ctx.search_text, ctx.recorded_from, ctx.recorded_before
                HAVING COUNT(*) = COUNT(r.id)
                """)
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
                .param(
                        "cutoff",
                        Timestamp.from(scope.requestAcceptedAt())
                )
                .query(
                        (
                                row,
                                index
                        ) -> new SearchQuestionContext(
                                row.getString("search_text"),
                                instant(row.getTimestamp("recorded_from")),
                                instant(row.getTimestamp("recorded_before")),
                                row.getObject(
                                        "recording_id",
                                        Long.class
                                )
                        )
                )
                .optional();
    }

    public void save(
            long questionId,
            SearchQuestionContext context
    ) {
        jdbc.sql("""
                INSERT INTO search_question_contexts (question_message_id, search_text, recorded_from, recorded_before,
                    recording_session_id) VALUES (:question, :text, :from, :before, :recording)
                ON CONFLICT (question_message_id) DO NOTHING
                """)
                .param(
                        "question",
                        questionId
                )
                .param(
                        "text",
                        context.searchText()
                )
                .param(
                        "from",
                        timestamp(context.recordedFrom())
                )
                .param(
                        "before",
                        timestamp(context.recordedBefore())
                )
                .param(
                        "recording",
                        context.recordingSessionId()
                )
                .update();
    }

    private Instant instant(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
    }
}
