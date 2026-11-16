package com.knot.backend.search.application.dto.result;

import java.time.Instant;
import java.util.UUID;

public record SearchAnswerWorkResult(
        long workspaceId,
        long memberId,
        long conversationId,
        long questionId,
        long answerId,
        UUID token,
        String question,
        Instant createdAt,
        Instant questionAcceptedAt,
        Instant requestAcceptedAt
) {

    public SearchAnswerWorkResult(
            long workspaceId,
            long memberId,
            long conversationId,
            long questionId,
            long answerId,
            UUID token,
            String question,
            Instant createdAt
    ) {
        this(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                question,
                createdAt,
                createdAt,
                createdAt
        );
    }
}
