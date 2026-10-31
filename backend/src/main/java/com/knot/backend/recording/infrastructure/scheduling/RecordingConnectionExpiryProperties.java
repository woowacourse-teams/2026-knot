package com.knot.backend.recording.infrastructure.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.connection-expiry")
public record RecordingConnectionExpiryProperties(
        boolean enabled,
        Duration interval,
        int batchSize,
        Duration grace,
        Duration disconnectedAfter
) {
}
