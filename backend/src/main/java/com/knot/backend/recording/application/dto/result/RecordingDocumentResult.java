package com.knot.backend.recording.application.dto.result;

import java.time.Instant;
import java.util.List;

public record RecordingDocumentResult(
        long recordingId,
        String title,
        Instant startedAt,
        int recordingDurationSeconds,
        RecordingDocumentProcessingStatus processingStatus,
        boolean retryable,
        List<Long> retryableJobIds,
        RecordingMeetingSummaryResult meetingSummary,
        RecordingTranscriptStatus transcriptStatus,
        TopicClassificationStatus topicClassificationStatus
) {

    public RecordingDocumentResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public static RecordingDocumentResult of(
            RecentRecordingItemResult recording,
            RecordingDocumentProcessingResult processing,
            RecordingMeetingSummaryResult meetingSummary,
            RecordingOutputStatus output
    ) {
        return new RecordingDocumentResult(
                recording.recordingId(),
                recording.title(),
                recording.startedAt(),
                recording.recordingDurationSeconds(),
                processing.status(),
                processing.retryable(),
                processing.retryableJobIds(),
                meetingSummary,
                output.transcriptStatus(),
                output.topicClassificationStatus()
        );
    }
}
