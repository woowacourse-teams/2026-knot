package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import java.util.List;

public record DocumentGenerationHomeResult(
        DocumentGenerationProcessingStatus status,
        boolean hasVisibleFailure,
        List<Long> retryableJobIds
) {
}
