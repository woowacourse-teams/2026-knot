package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicOperationResponse(
        long operationId,
        long workspaceId,
        String type,
        DocumentTopicOperationStatus status,
        Instant createdAt
) {

    public static DocumentTopicOperationResponse from(DocumentTopicOperationResult result) {
        return new DocumentTopicOperationResponse(
                result.id(),
                result.workspaceId(),
                "REORGANIZATION",
                result.status(),
                result.createdAt()
        );
    }
}
