package com.knot.backend.global.config;

import com.knot.backend.recording.application.TranscriptionClient;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.infrastructure.transcription.ClovaSpeechClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClovaSpeechProperties.class)
@ConditionalOnProperty(prefix = "knot.transcription", name = "provider", havingValue = "clova")
public class ClovaSpeechConfig {
    @Bean
    public TranscriptionClient clovaSpeechClient(
            ClovaSpeechProperties clova,
            TranscriptionProperties worker,
            RecordingAudioChunksProperties chunks,
            ObjectMapper mapper
    ) {
        if (worker.enabled()) {
            clova.validate();
            worker.validateWorkerConfiguration();
        }
        if (worker.enabled() && chunks.incrementalEnabled()) {
            throw new RecordingException(RecordingErrorCode.AI_INVALID_CONFIGURATION);
        }
        Duration timeout = Duration.ofSeconds(5);
        if (worker.enabled()) {
            timeout = worker.connectTimeout();
        }
        return new ClovaSpeechClient(
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                mapper,
                clova,
                worker
        );
    }
}
