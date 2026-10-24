package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicCancellationResult(
        long operationId,
        long workspaceId,
        String type,
        DocumentTopicOperationStatus status,
        Instant cancelRequestedAt,
        Instant finishedAt
) {

    public static DocumentTopicCancellationResult from(DocumentTopicOperation operation) {
        String type = "REORGANIZATION";
        if (operation.getKind() == DocumentTopicOperationKind.RESTORE) {
            type = "RESTORATION";
        }
        return new DocumentTopicCancellationResult(
                operation.getId(),
                operation.getWorkspaceId(),
                type,
                operation.getStatus(),
                operation.getCancelRequestedAt(),
                operation.getFinishedAt()
        );
    }
}
