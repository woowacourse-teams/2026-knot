package com.knot.backend.recording.application.dto.result;

import java.util.UUID;

public record MeetingSummaryTask(
        long recordingId,
        long transcriptId,
        UUID token,
        long attempt,
        String transcript
) {

    @Override
    public String toString() {
        return "MeetingSummaryTask[recordingId=" + recordingId + ", attempt=" + attempt + ", transcript=REDACTED]";
    }
}
