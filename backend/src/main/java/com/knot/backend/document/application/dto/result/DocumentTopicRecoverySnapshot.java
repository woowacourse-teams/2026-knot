package com.knot.backend.document.application.dto.result;

import java.time.Instant;

public record DocumentTopicRecoverySnapshot(
        long versionId,
        long reorganizationOperationId,
        Instant completedAt,
        long affectedDocumentCount
) {
}
