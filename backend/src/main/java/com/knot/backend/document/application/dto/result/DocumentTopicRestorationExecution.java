package com.knot.backend.document.application.dto.result;

import java.util.UUID;

public record DocumentTopicRestorationExecution(
        long workspaceId,
        long operationId,
        UUID token,
        boolean readyToApply
) {
}
