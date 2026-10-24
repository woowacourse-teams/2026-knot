package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import java.time.Instant;

public record DocumentTopicOperationSnapshot(
        long operationId,
        DocumentTopicOperationKind kind,
        DocumentTopicOperationStatus status,
        int retryCount,
        long targetCount,
        long processedCount,
        long changedCount,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long targetVersionId
) {
}
