package com.knot.backend.document.application.dto.result;

import java.util.List;

public record DocumentRecordingListResult(
        List<DocumentRecordingItemResult> items,
        String nextCursor
) {
}
