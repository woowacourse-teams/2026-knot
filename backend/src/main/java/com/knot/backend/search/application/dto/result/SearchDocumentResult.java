package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.domain.model.SearchEvidenceVersion;
import java.time.Instant;

public record SearchDocumentResult(
        long documentId,
        String title,
        String topic,
        String content,
        Instant recordingStartedAt,
        Instant createdAt,
        String sourceVersion
) {

    public static SearchDocumentResult from(SearchDocumentRecord record) {
        return new SearchDocumentResult(
                record.documentId(),
                record.title(),
                record.topic(),
                record.content(),
                record.recordingStartedAt(),
                record.createdAt(),
                SearchEvidenceVersion.fromDocument(
                        record.title(),
                        record.topic(),
                        record.content()
                )
        );
    }

    public SearchDocumentResult(
            long documentId,
            String title,
            String topic,
            String content,
            Instant recordingStartedAt,
            Instant createdAt
    ) {
        this(
                documentId,
                title,
                topic,
                content,
                recordingStartedAt,
                createdAt,
                null
        );
    }

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
