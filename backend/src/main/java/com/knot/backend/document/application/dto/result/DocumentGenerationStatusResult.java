package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import java.util.List;

public record DocumentGenerationStatusResult(
        DocumentGenerationProcessingStatus status,
        List<Long> retryableJobIds
) {

    public DocumentGenerationStatusResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }
}
