package com.knot.backend.search.application.dto.query;

import java.time.Instant;

public record SearchQuestionContext(
        String searchText,
        Instant recordedFrom,
        Instant recordedBefore,
        Long recordingSessionId
) {
}
