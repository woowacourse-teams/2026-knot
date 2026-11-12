package com.knot.backend.global.config;

import com.knot.backend.recording.application.TranscriptionClient;
import com.knot.backend.recording.infrastructure.transcription.AiTranscriptionHttpClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TranscriptionProperties.class)
public class TranscriptionConfig {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "knot.transcription", name = "provider", havingValue = "local", matchIfMissing = true)
    public TranscriptionClient transcriptionClient(
            TranscriptionProperties properties,
            ObjectMapper mapper
    ) {
        properties.validateEnabledConfiguration();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.enabled() ? properties.connectTimeout() : Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new AiTranscriptionHttpClient(
                client,
                mapper,
                properties
        );
    }
}
