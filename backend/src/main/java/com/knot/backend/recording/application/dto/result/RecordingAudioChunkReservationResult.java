package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import java.time.Instant;
import java.util.Map;

public record RecordingAudioChunkReservationResult(
        boolean created,
        int sequence,
        RecordingAudioChunkStatus status,
        String uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {
    public RecordingAudioChunkReservationResult {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }

    @Override
    public String toString() {
        return "RecordingAudioChunkReservationResult[sequence=" + sequence + ", status=" + status
                + ", uploadUrl=REDACTED]";
    }
}
