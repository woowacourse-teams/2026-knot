package com.knot.backend.recording.application.dto.result;

public record RecordingOutputStatus(
        RecordingTranscriptStatus transcriptStatus,
        TopicClassificationStatus topicClassificationStatus,
        boolean summaryEligible
) {
}
