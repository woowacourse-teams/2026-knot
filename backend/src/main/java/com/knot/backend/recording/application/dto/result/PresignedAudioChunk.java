package com.knot.backend.recording.application.dto.result;

import java.time.Instant;
import java.util.Map;

public record PresignedAudioChunk(
        String uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {

    public PresignedAudioChunk {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }

    @Override
    public String toString() {
        return "PresignedAudioChunk[uploadUrl=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
