package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import java.time.Duration;

public interface DocumentCategoryAssigner {

    DocumentCategoryDecision assign(
            DocumentCategoryInput input,
            Duration timeout
    );
}
