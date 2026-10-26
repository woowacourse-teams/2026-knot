package com.knot.backend.recording.application.dto.result;

import java.util.List;

public record RecordingTranscriptResult(
        long recordingId,
        long transcriptId,
        String transcriptText,
        List<TranscriptSegmentResult> segments
) {

    public RecordingTranscriptResult {
        segments = List.copyOf(segments);
    }
}
