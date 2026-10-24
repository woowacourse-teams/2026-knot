package com.knot.backend.document.presentation.dto.response;

import com.knot.backend.document.application.dto.result.DocumentTopicRecoverySnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicStatusResult;
import com.knot.backend.document.application.dto.result.DocumentTopicStatusOperationResult;

public record DocumentTopicStatusResponse(
        long workspaceId,
        DocumentTopicStatusOperationResult operation,
        DocumentTopicRecoverySnapshot restorableVersion,
        boolean canRestore
) {

    public static DocumentTopicStatusResponse from(DocumentTopicStatusResult result) {
        return new DocumentTopicStatusResponse(
                result.workspaceId(),
                result.operation(),
                result.restorableVersion(),
                result.canRestore()
        );
    }
}
