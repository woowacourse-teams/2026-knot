package com.knot.backend.recording.application.dto.result;

import java.time.Instant;
import java.util.List;

public record RecentRecordingRowResult(
        long recordingId,
        String title,
        Instant startedAt,
        int recordingDurationSeconds,
        RecordingDocumentProcessingStatus processingStatus,
        boolean retryable,
        List<Long> retryableJobIds
) {

    public RecentRecordingRowResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public static RecentRecordingRowResult of(
            RecentRecordingItemResult recording,
            RecordingDocumentProcessingResult processing
    ) {
        return new RecentRecordingRowResult(
                recording.recordingId(),
                recording.title(),
                recording.startedAt(),
                recording.recordingDurationSeconds(),
                processing.status(),
                processing.retryable(),
                processing.retryableJobIds()
        );
    }
}
