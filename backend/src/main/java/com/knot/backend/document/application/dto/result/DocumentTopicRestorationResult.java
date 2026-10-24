package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicRestorationResult(
        long operationId,
        long workspaceId,
        DocumentTopicOperationStatus status,
        long targetVersionId,
        Instant createdAt,
        boolean replayed
) {

    public static DocumentTopicRestorationResult from(
            DocumentTopicOperation operation,
            boolean replayed
    ) {
        return new DocumentTopicRestorationResult(
                operation.getId(),
                operation.getWorkspaceId(),
                operation.getStatus()
                        .reportedStatus(operation.getRetryCount()),
                operation.getTargetVersionId(),
                operation.getCreatedAt(),
                replayed
        );
    }
}
