package com.knot.backend.recording.presentation.dto.response;

public record RecordingSpeakerCountResponse(
        long recordingId,
        Integer speakerCount
) {
}
