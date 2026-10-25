package com.knot.backend.recording.application.dto.result;

public record RecordingTitleTask(
        long recordingId,
        int attempt,
        String transcript
) {

    @Override
    public String toString() {
        return "RecordingTitleTask[recordingId=" + recordingId + ", attempt=" + attempt + ", transcript=REDACTED]";
    }
}
