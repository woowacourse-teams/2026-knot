package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.domain.SearchToolException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SearchToolScope(
        long workspaceId,
        long memberId,
        long conversationId,
        long questionId,
        long answerId,
        UUID token,
        Instant questionAcceptedAt,
        Instant requestAcceptedAt,
        Instant startedAt,
        Instant expiresAt,
        Long recordingSessionId,
        LocalDate dateContext
) {

    public SearchToolScope(
            long workspaceId,
            long memberId,
            long conversationId,
            long questionId,
            long answerId,
            UUID token,
            Instant startedAt,
            Instant expiresAt,
            Long recordingSessionId
    ) {
        this(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                startedAt,
                startedAt,
                startedAt,
                expiresAt,
                recordingSessionId
        );
    }

    public SearchToolScope(
            long workspaceId,
            long memberId,
            long conversationId,
            long questionId,
            long answerId,
            UUID token,
            Instant questionAcceptedAt,
            Instant requestAcceptedAt,
            Instant startedAt,
            Instant expiresAt,
            Long recordingSessionId
    ) {
        this(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                questionAcceptedAt,
                requestAcceptedAt,
                startedAt,
                expiresAt,
                recordingSessionId,
                null
        );
    }

    public SearchToolScope {
        if (workspaceId <= 0 || memberId <= 0 || conversationId <= 0 || questionId <= 0 || answerId <= 0) {
            throw new SearchToolException("INVALID_TOOL_ARGUMENTS");
        }
        if (token == null || questionAcceptedAt == null || requestAcceptedAt == null || startedAt == null
                || expiresAt == null) {
            throw new SearchToolException("INVALID_TOOL_ARGUMENTS");
        }
        if (!expiresAt.isAfter(startedAt)) {
            throw new SearchToolException("INVALID_TOOL_ARGUMENTS");
        }
        if (recordingSessionId != null && recordingSessionId <= 0) {
            throw new SearchToolException("INVALID_TOOL_ARGUMENTS");
        }
    }

    public SearchToolScope forMeeting(long recordingId) {
        return new SearchToolScope(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                questionAcceptedAt,
                requestAcceptedAt,
                startedAt,
                expiresAt,
                recordingId,
                dateContext
        );
    }

    public SearchToolScope(
            long workspaceId,
            long memberId,
            long conversationId,
            long questionId,
            long answerId,
            UUID token,
            Instant startedAt,
            Instant expiresAt
    ) {
        this(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                startedAt,
                startedAt,
                startedAt,
                expiresAt,
                null
        );
    }

    public SearchToolScope withDateContext(LocalDate date) {
        return new SearchToolScope(
                workspaceId,
                memberId,
                conversationId,
                questionId,
                answerId,
                token,
                questionAcceptedAt,
                requestAcceptedAt,
                startedAt,
                expiresAt,
                recordingSessionId,
                date
        );
    }

    public static SearchToolScope from(
            SearchAnswerWorkResult work,
            Instant startedAt,
            Instant expiresAt
    ) {
        return new SearchToolScope(
                work.workspaceId(),
                work.memberId(),
                work.conversationId(),
                work.questionId(),
                work.answerId(),
                work.token(),
                work.questionAcceptedAt(),
                work.requestAcceptedAt(),
                startedAt,
                expiresAt,
                null
        );
    }
}
