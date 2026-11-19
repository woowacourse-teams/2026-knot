package com.knot.backend.search;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

public class SearchFixtures {
    public static final Instant TIME = Instant.parse("2026-10-11T00:00:00.123456Z");

    private final JdbcClient jdbc;

    public SearchFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long saveConversation(
            long workspaceId,
            long memberId,
            String question,
            String answer
    ) {
        long id = saveEmptyConversation(
                workspaceId,
                memberId,
                TIME
        );
        saveMessage(
                id,
                "USER",
                1,
                question,
                "RECEIVED"
        );
        saveMessage(
                id,
                "ASSISTANT",
                2,
                answer,
                "STREAMING"
        );
        return id;
    }

    public long saveEmptyConversation(
            long workspaceId,
            long memberId,
            Instant time
    ) {
        return jdbc.sql("""
                INSERT INTO search_conversations (workspace_id, member_id, created_at, updated_at, visible_in_list)
                VALUES (:workspaceId, :memberId, :time, :time, TRUE) RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "time",
                        Timestamp.from(time)
                )
                .query(Long.class)
                .single();
    }

    public void saveMessage(
            long conversationId,
            String role,
            int sequence,
            String content,
            String status
    ) {
        jdbc.sql("""
                INSERT INTO search_messages (conversation_id, role, sequence, content, status, created_at)
                VALUES (:conversationId, :role, :sequence, :content, :status, :time)
                """)
                .param(
                        "conversationId",
                        conversationId
                )
                .param(
                        "role",
                        role
                )
                .param(
                        "sequence",
                        sequence
                )
                .param(
                        "content",
                        content
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "time",
                        Timestamp.from(TIME)
                )
                .update();
        if ("ASSISTANT".equals(role) && "COMPLETED".equals(status)) {
            jdbc.sql(
                    "UPDATE search_messages SET answer_kind='UNVERIFIED_ANSWER' WHERE conversation_id=:id AND sequence=:sequence"
            )
                    .param(
                            "id",
                            conversationId
                    )
                    .param(
                            "sequence",
                            sequence
                    )
                    .update();
        }
    }

    public void setVisible(
            long conversationId,
            boolean visible
    ) {
        jdbc.sql("UPDATE search_conversations SET visible_in_list = :visible WHERE id = :id")
                .param(
                        "visible",
                        visible
                )
                .param(
                        "id",
                        conversationId
                )
                .update();
    }

    public void setUpdatedAt(
            long conversationId,
            Instant time
    ) {
        jdbc.sql("UPDATE search_conversations SET updated_at = :time WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(time)
                )
                .param(
                        "id",
                        conversationId
                )
                .update();
    }

    public void setAnswerStatus(
            long conversationId,
            String status
    ) {
        jdbc.sql("UPDATE search_messages SET status = :status WHERE conversation_id = :id AND sequence = 2")
                .param(
                        "status",
                        status
                )
                .param(
                        "id",
                        conversationId
                )
                .update();
    }
}
