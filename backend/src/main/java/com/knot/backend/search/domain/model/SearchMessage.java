package com.knot.backend.search.domain.model;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "search_messages")
public class SearchMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SearchMessageRole role;

    @Column(nullable = false, updatable = false)
    private int sequence;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SearchMessageStatus status;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SearchMessage() {}

    private SearchMessage(
            long conversationId,
            int sequence,
            SearchMessageRole role,
            String content,
            SearchMessageStatus status,
            Instant createdAt
    ) {
        validateIdentity(
                conversationId,
                sequence
        );
        if (createdAt == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        this.conversationId = conversationId;
        this.sequence = sequence;
        this.role = role;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static SearchMessage receiveQuestion(
            long conversationId,
            int sequence,
            String content,
            Instant createdAt
    ) {
        SearchQuestionText.validate(content);
        return new SearchMessage(
                conversationId,
                sequence,
                SearchMessageRole.USER,
                content,
                SearchMessageStatus.RECEIVED,
                createdAt
        );
    }

    public static SearchMessage startAnswer(
            long conversationId,
            int sequence,
            Instant createdAt
    ) {
        return new SearchMessage(
                conversationId,
                sequence,
                SearchMessageRole.ASSISTANT,
                "",
                SearchMessageStatus.STREAMING,
                createdAt
        );
    }

    public void appendContent(String text) {
        requireStreamingAnswer();
        if (text == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        content += text;
    }

    public void stop() {
        requireStreamingAnswer();
        status = SearchMessageStatus.STOPPED;
    }

    public void complete() {
        requireStreamingAnswer();
        if (content.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        status = SearchMessageStatus.COMPLETED;
    }

    public void fail(String code) {
        requireStreamingAnswer();
        if (code == null || code.isBlank() || code.length() > 50) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        failureCode = code;
        status = SearchMessageStatus.FAILED;
    }

    public void completeGuidance(String guidance) {
        requireStreamingAnswer();
        if (guidance == null || guidance.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        content = guidance;
        failureCode = null;
        status = SearchMessageStatus.COMPLETED;
    }

    public void retry() {
        if (role != SearchMessageRole.ASSISTANT || status != SearchMessageStatus.FAILED) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        content = "";
        failureCode = null;
        status = SearchMessageStatus.STREAMING;
    }

    private void requireStreamingAnswer() {
        if (role != SearchMessageRole.ASSISTANT || status != SearchMessageStatus.STREAMING) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    private static void validateIdentity(
            long conversationId,
            int sequence
    ) {
        if (conversationId <= 0 || sequence <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }
}
