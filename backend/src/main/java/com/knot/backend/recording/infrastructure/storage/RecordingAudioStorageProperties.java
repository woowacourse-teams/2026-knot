package com.knot.backend.recording.infrastructure.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.audio-storage")
public record RecordingAudioStorageProperties(
        String region,
        String bucket,
        String keyPrefix,
        Duration uploadUrlTtl
) {

    public boolean isConfigured() {
        return hasText(region) && hasText(bucket);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
