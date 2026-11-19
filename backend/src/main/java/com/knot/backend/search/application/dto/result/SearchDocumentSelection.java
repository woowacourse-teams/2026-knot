package com.knot.backend.search.application.dto.result;

public record SearchDocumentSelection(
        long documentId,
        String title,
        int ordinal
) {
}
