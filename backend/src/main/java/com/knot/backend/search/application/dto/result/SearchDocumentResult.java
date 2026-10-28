package com.knot.backend.search.application.dto.result;

import java.time.Instant;

public record SearchDocumentResult(
        long documentId,
        String title,
        String topic,
        String content,
        Instant recordingStartedAt,
        Instant createdAt
) {

    public SearchDocumentResult(
            long documentId,
            String title,
            String topic,
            String content
    ) {
        this(
                documentId,
                title,
                topic,
                content,
                null,
                null
        );
    }
}
