package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.global.config.RecordingAudioChunksProperties;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.application.RecordingAudioAssembler;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.infrastructure.audio.PcmWavAssembler;
import java.io.InputStream;
import java.nio.file.Path;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RecordingAudioStorageProperties.class, RecordingAudioChunksProperties.class})
public class RecordingAudioStorageConfig {
    private static final Duration STORAGE_CALL_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RecordingAudioStorage recordingAudioStorage(RecordingAudioStorageProperties properties) {
        if (!properties.isConfigured()) {
            return new UnconfiguredRecordingAudioStorage();
        }
        return new S3RecordingAudioStorage(
                presigner(properties),
                client(properties),
                properties.bucket(),
                properties.keyPrefix(),
                properties.uploadUrlTtl()
        );
    }

    @Bean
    public RecordingAudioChunkStorage recordingAudioChunkStorage(
            RecordingAudioStorageProperties properties,
            Clock clock
    ) {
        if (!properties.isConfigured()) {
            return new RecordingAudioChunkStorage() {
                @Override
                public PresignedAudioChunk presignUpload(
                        String storageKey,
                        long byteLength,
                        String checksumSha256,
                        Instant expiresAt
                ) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
                }

                @Override
                public StoredAudioChunk findStoredObject(String storageKey) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
                }
            };
        }
        return new S3RecordingAudioChunkStorage(
                presigner(properties),
                client(properties),
                properties.bucket(),
                properties.keyPrefix(),
                clock
        );
    }

    @Bean
    public RecordingAudioFinalStorage recordingAudioFinalStorage(RecordingAudioStorageProperties properties) {
        if (!properties.isConfigured()) {
            return new RecordingAudioFinalStorage() {
                @Override
                public InputStream openChunk(AudioManifestPart part) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
                }

                @Override
                public StoredAudioChunk uploadFinal(
                        String storageKey,
                        Path file,
                        long byteLength,
                        String checksumSha256
                ) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
                }

                @Override
                public StoredAudioChunk findStoredFinal(String storageKey) {
                    throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
                }
            };
        }
        return new S3RecordingAudioFinalStorage(
                client(
                        properties,
                        Duration.ofMinutes(2)
                ),
                properties.bucket(),
                properties.keyPrefix()
        );
    }

    @Bean
    public RecordingAudioAssembler recordingAudioAssembler(RecordingAudioFinalStorage storage) {
        return new PcmWavAssembler(storage);
    }

    // NCP Object Storage 등 S3 호환 저장소를 같은 코드로 쓰기 위해 endpoint를 지정하고 path-style 주소를
    // 사용한다.
    private S3Presigner presigner(RecordingAudioStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build()
                )
                .build();
    }

    private S3Client client(RecordingAudioStorageProperties properties) {
        return client(
                properties,
                STORAGE_CALL_TIMEOUT
        );
    }

    private S3Client client(
            RecordingAudioStorageProperties properties,
            Duration timeout
    ) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build()
                )
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallTimeout(timeout)
                                .build()
                )
                .build();
    }

    private AwsCredentialsProvider credentials(RecordingAudioStorageProperties properties) {
        if (!properties.hasStaticCredentials()) {
            return DefaultCredentialsProvider.builder()
                    .build();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        properties.accessKey(),
                        properties.secretKey()
                )
        );
    }
}
