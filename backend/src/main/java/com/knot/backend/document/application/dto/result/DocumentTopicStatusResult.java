package com.knot.backend.document.application.dto.result;

public record DocumentTopicStatusResult(
        long workspaceId,
        DocumentTopicStatusOperationResult operation,
        DocumentTopicRecoverySnapshot restorableVersion,
        boolean canRestore
) {
}
