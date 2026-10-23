package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import java.time.Duration;

public record DocumentCategoryAttempt(
        DocumentCategoryInput input,
        Duration timeout,
        String catalogVersion
) {

    public boolean canCall() {
        return !timeout.isZero();
    }

    @Override
    public String toString() {
        return "DocumentCategoryAttempt[redacted]";
    }
}
