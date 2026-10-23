package com.knot.backend.document.application.dto.request;

import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.model.DocumentCategory;
import java.util.List;

public record DocumentCategoryInput(
        String agendaTopic,
        DocumentGenerationResult document,
        List<DocumentCategory> categories
) {

    public DocumentCategoryInput {
        categories = List.copyOf(categories);
    }

    @Override
    public String toString() {
        return "DocumentCategoryInput[redacted]";
    }
}
