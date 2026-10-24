package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicStatusOperationResult(
        long operationId,
        String type,
        DocumentTopicOperationStatus status,
        Long targetVersionId,
        Long targetDocumentCount,
        long processedDocumentCount,
        Long changedDocumentCount,
        boolean cancellable,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        DocumentTopicFailureResult failure
) {
}
