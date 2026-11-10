package com.knot.backend.recording.infrastructure.scheduling;

import com.knot.backend.recording.domain.model.RecordingConnectionPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecordingConnectionExpiryProperties.class)
public class RecordingConnectionPolicyConfig {

    @Bean
    public RecordingConnectionPolicy recordingConnectionPolicy(RecordingConnectionExpiryProperties properties) {
        return new RecordingConnectionPolicy(
                properties.grace(),
                properties.disconnectedAfter()
        );
    }
}
