package com.knot.backend.document.application.dto.result;
public record DocumentTopicCandidate(
        String key,
        Long existingCategoryId,
        String name,
        String description,
        boolean wasCandidate
) {
}
