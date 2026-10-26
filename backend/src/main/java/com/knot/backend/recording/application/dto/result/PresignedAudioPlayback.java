package com.knot.backend.recording.application.dto.result;

import java.time.Instant;

public record PresignedAudioPlayback(
        String playbackUrl,
        Instant expiresAt
) {
}
