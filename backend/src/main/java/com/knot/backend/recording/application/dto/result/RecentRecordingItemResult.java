package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingTitle;
import java.time.Instant;

public record RecentRecordingItemResult(
        long recordingId,
        String title,
        Instant startedAt,
        int recordingDurationSeconds
) {

    public static RecentRecordingItemResult of(
            long recordingId,
            Instant startedAt,
            long recordingDurationMillis,
            String starterNickname,
            String publicAiTitle
    ) {
        return new RecentRecordingItemResult(
                recordingId,
                RecordingTitle.display(
                        publicAiTitle,
                        starterNickname
                ),
                startedAt,
                Math.toIntExact(recordingDurationMillis / 1000)
        );
    }
}
