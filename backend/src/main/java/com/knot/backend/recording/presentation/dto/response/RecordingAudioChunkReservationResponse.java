package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import java.time.Instant;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import java.util.Map;

public record RecordingAudioChunkReservationResponse(
        int sequence,
        RecordingAudioChunkStatus status,
        String uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {

    public static RecordingAudioChunkReservationResponse from(RecordingAudioChunkReservationResult result) {
        return new RecordingAudioChunkReservationResponse(
                result.sequence(),
                result.status(),
                result.uploadUrl(),
                result.expiresAt(),
                result.requiredHeaders()
        );
    }

    @Override
    public String toString() {
        return "RecordingAudioChunkReservationResponse[sequence=" + sequence + ", status=" + status + "]";
    }
}
