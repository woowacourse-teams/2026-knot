package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentTopicAssignment;

public record DocumentTopicRestorationTarget(
        long documentId,
        DocumentTopicAssignment expected,
        DocumentTopicAssignment restored,
        boolean prepared
) {
}
