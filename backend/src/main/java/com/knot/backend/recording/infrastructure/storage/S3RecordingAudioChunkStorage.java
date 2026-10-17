package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class S3RecordingAudioChunkStorage implements RecordingAudioChunkStorage {
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final int NOT_FOUND_STATUS = 404;
    private static final Duration SIGNING_MARGIN = Duration.ofSeconds(1);

    private final S3Presigner presigner;
    private final S3Client client;
    private final String bucket;
    private final String keyPrefix;
    private final Clock clock;

    public S3RecordingAudioChunkStorage(
            S3Presigner presigner,
            S3Client client,
            String bucket,
            String keyPrefix,
            Clock clock
    ) {
        this.presigner = presigner;
        this.client = client;
        this.bucket = bucket;
        this.keyPrefix = java.util.Objects.requireNonNullElse(
                keyPrefix,
                ""
        );
        this.clock = clock;
    }

    @Override
    public PresignedAudioChunk presignUpload(
            String storageKey,
            long byteLength,
            String checksumSha256,
            Instant expiresAt
    ) {
        Duration duration = Duration.between(
                clock.instant(),
                expiresAt
        )
                .minus(SIGNING_MARGIN);
        if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
        try {
            PresignedPutObjectRequest signed = presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(duration)
                            .putObjectRequest(
                                    PutObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(keyPrefix + storageKey)
                                            .contentType(CONTENT_TYPE)
                                            .contentLength(byteLength)
                                            .checksumSHA256(checksumSha256)
                                            .ifNoneMatch("*")
                                            .build()
                            )
                            .build()
            );
            // SDK는 자체 시계로 서명한다. DB에 기록한 상한보다 늦은 URL은 클라이언트에 보내지 않는다.
            if (signed.expiration()
                    .isAfter(expiresAt)
                    || !signed.expiration()
                            .isAfter(clock.instant())) {
                throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
            }
            return new PresignedAudioChunk(
                    signed.url()
                            .toString(),
                    signed.expiration(),
                    requiredHeaders(signed.signedHeaders())
            );
        } catch (SdkException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    @Override
    public StoredAudioChunk findStoredObject(String storageKey) {
        try {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(keyPrefix + storageKey)
                            .checksumMode(ChecksumMode.ENABLED)
                            .build()
            );
            return new StoredAudioChunk(
                    true,
                    java.util.Objects.requireNonNullElse(
                            response.contentLength(),
                            -1L
                    ),
                    response.contentType(),
                    response.checksumSHA256(),
                    response.versionId()
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == NOT_FOUND_STATUS) {
                return StoredAudioChunk.missing();
            }
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        } catch (SdkException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    private Map<String, String> requiredHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : signedHeaders.entrySet()) {
            String name = header.getKey()
                    .toLowerCase(Locale.ROOT);
            // 브라우저가 결정하는 금지 헤더는 FE가 직접 설정하지 않는다.
            if (!"host".equals(name) && !"content-length".equals(name)) {
                headers.put(
                        name,
                        String.join(
                                ",",
                                header.getValue()
                        )
                );
            }
        }
        return headers;
    }
}
