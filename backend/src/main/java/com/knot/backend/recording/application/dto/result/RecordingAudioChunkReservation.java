package com.knot.backend.recording.application.dto.result;

import java.time.Instant;

public record RecordingAudioChunkReservation(
        boolean created,
        int sequence,
        String storageKey,
        long byteLength,
        String checksumSha256,
        Instant expiresAt,
        RecordingAudioChunkReservationResult completed
) {
}
