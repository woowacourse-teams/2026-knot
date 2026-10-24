package com.knot.backend.document.application.dto.result;

public record DocumentTopicRestorationSource(
        long versionId,
        long sourceOperationId,
        int changedCount
) {
}
