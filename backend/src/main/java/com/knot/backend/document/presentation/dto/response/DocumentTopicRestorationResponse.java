package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationResult;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicRestorationResponse(
        long operationId,
        long workspaceId,
        String type,
        DocumentTopicOperationStatus status,
        long targetVersionId,
        Instant createdAt
) {

    public static DocumentTopicRestorationResponse from(DocumentTopicRestorationResult result) {
        return new DocumentTopicRestorationResponse(
                result.operationId(),
                result.workspaceId(),
                "RESTORATION",
                result.status(),
                result.targetVersionId(),
                result.createdAt()
        );
    }
}
