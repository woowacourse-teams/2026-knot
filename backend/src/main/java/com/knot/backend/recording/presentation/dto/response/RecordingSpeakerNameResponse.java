package com.knot.backend.recording.presentation.dto.response;

public record RecordingSpeakerNameResponse(
        long recordingId,
        int speakerNumber,
        String displayName
) {
}
