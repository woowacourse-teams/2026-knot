package com.knot.backend.recording.domain.model;

import java.time.Instant;

public record RecordingAudioCleanupPolicy(
        RecordingAudioCleanupScope scope,
        Instant maxUrlExpiresAt
) {
}
