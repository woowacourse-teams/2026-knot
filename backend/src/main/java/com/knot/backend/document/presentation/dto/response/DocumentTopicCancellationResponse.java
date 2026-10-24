package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentTopicCancellationResult;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicCancellationResponse(
        long operationId,
        long workspaceId,
        String type,
        DocumentTopicOperationStatus status,
        Instant cancelRequestedAt,
        Instant finishedAt
) {

    public static DocumentTopicCancellationResponse from(DocumentTopicCancellationResult result) {
        return new DocumentTopicCancellationResponse(
                result.operationId(),
                result.workspaceId(),
                result.type(),
                result.status(),
                result.cancelRequestedAt(),
                result.finishedAt()
        );
    }
}
