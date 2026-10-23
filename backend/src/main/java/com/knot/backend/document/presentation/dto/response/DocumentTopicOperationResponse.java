package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentTopicOperationResponse(
        UUID operationId,
        DocumentTopicOperationStatus status,
        int targetCount,
        int processedCount,
        int changedCount,
        Instant createdAt,
        Instant deadlineAt
) {

    public static DocumentTopicOperationResponse from(DocumentTopicOperationResult result) {
        return new DocumentTopicOperationResponse(
                result.operationId(),
                result.status(),
                result.targetCount(),
                result.processedCount(),
                result.changedCount(),
                result.createdAt(),
                result.deadlineAt()
        );
    }
}
