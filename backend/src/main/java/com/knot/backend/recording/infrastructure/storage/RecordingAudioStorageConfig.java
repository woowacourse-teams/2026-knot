package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.recording.application.RecordingAudioStorage;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecordingAudioStorageProperties.class)
public class RecordingAudioStorageConfig {
    private static final Duration STORAGE_CALL_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RecordingAudioStorage recordingAudioStorage(RecordingAudioStorageProperties properties) {
        if (!properties.isConfigured()) {
            return new UnconfiguredRecordingAudioStorage();
        }
        Region region = Region.of(properties.region());
        return new S3RecordingAudioStorage(
                S3Presigner.builder()
                        .region(region)
                        .build(),
                S3Client.builder()
                        .region(region)
                        .overrideConfiguration(
                                ClientOverrideConfiguration.builder()
                                        .apiCallTimeout(STORAGE_CALL_TIMEOUT)
                                        .build()
                        )
                        .build(),
                properties.bucket(),
                properties.keyPrefix(),
                properties.uploadUrlTtl()
        );
    }
}
