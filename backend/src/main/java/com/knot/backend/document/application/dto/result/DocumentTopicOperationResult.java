package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentTopicOperationResult(
        Long id,
        long workspaceId,
        UUID operationId,
        DocumentTopicOperationStatus status,
        int targetCount,
        int processedCount,
        int changedCount,
        Instant createdAt,
        Instant deadlineAt,
        boolean replayed
) {

    public static DocumentTopicOperationResult from(
            DocumentTopicOperation operation,
            boolean replayed
    ) {
        return new DocumentTopicOperationResult(
                operation.getId(),
                operation.getWorkspaceId(),
                operation.getOperationId(),
                operation.getStatus()
                        .reportedStatus(operation.getRetryCount()),
                operation.getTargetCount(),
                operation.getProcessedCount(),
                operation.getChangedCount(),
                operation.getCreatedAt(),
                operation.getDeadlineAt(),
                replayed
        );
    }
}
