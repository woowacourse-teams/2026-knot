package com.knot.backend.document.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DocumentTopicReorganizationRequest(@NotNull UUID requestId) {
}
