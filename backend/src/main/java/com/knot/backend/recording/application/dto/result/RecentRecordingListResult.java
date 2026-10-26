package com.knot.backend.recording.application.dto.result;

import java.util.List;

public record RecentRecordingListResult(
        List<RecentRecordingItemResult> items,
        String nextCursor
) {
}
