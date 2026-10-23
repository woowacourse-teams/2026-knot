package com.knot.backend.document.application;

import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import java.time.Duration;

public record DocumentCategoryPublished(
        long workspaceId,
        long jobId,
        long documentId,
        DocumentCategoryAssignmentSource source,
        String ruleVersion,
        String reason,
        int attempts,
        Duration elapsed
) {
}
