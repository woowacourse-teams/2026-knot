package com.knot.backend.recording.infrastructure.scheduling;

import com.knot.backend.recording.application.RecordingConnectionExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "recording.connection-expiry", name = "enabled", havingValue = "true")
public class RecordingConnectionExpiryConfig {

    @Bean
    public RecordingConnectionExpiryScheduler recordingConnectionExpiryScheduler(
            RecordingConnectionExpiryService expiryService,
            RecordingConnectionExpiryProperties properties
    ) {
        return new RecordingConnectionExpiryScheduler(
                expiryService,
                properties
        );
    }
}
