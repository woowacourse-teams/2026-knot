package com.knot.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.audio-chunks")
public record RecordingAudioChunksProperties(boolean enabled) {
}
