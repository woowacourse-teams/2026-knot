package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.recording.application.RecordingAudioFinalStorage;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.AudioStorageAccessDeniedException;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.io.InputStream;
import java.nio.file.Path;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3RecordingAudioFinalStorage implements RecordingAudioFinalStorage {
    private final S3Client client;
    private final String bucket;
    private final String keyPrefix;

    public S3RecordingAudioFinalStorage(
            S3Client client,
            String bucket,
            String keyPrefix
    ) {
        this.client = client;
        this.bucket = bucket;
        this.keyPrefix = java.util.Objects.requireNonNullElse(
                keyPrefix,
                ""
        );
    }

    @Override
    public InputStream openChunk(AudioManifestPart part) {
        if (part.isMissing() || part.versionId()
                .isBlank() || "null".equals(part.versionId())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        try {
            return client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(keyPrefix + part.storageKey())
                            .versionId(part.versionId())
                            .checksumMode(ChecksumMode.ENABLED)
                            .build()
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 403 || exception.statusCode() == 401) {
                throw new AudioStorageAccessDeniedException(exception);
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

    @Override
    public StoredAudioChunk uploadFinal(
            String storageKey,
            Path file,
            long byteLength,
            String checksumSha256
    ) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(keyPrefix + storageKey)
                            .contentType("audio/wav")
                            .contentLength(byteLength)
                            .checksumSHA256(checksumSha256)
                            .ifNoneMatch("*")
                            .build(),
                    RequestBody.fromFile(file)
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 403 || exception.statusCode() == 401) {
                throw new AudioStorageAccessDeniedException(exception);
            }
            if (exception.statusCode() != 412) {
                throw new RecordingException(
                        RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                        exception
                );
            }
        } catch (SdkException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
        StoredAudioChunk stored = findStoredFinal(storageKey);
        if (!stored.exists() || stored.byteLength() != byteLength || !"audio/wav".equals(stored.contentType())
                || !checksumSha256.equals(stored.checksumSha256()) || stored.versionId() == null || stored.versionId()
                        .isBlank()
                || "null".equals(stored.versionId())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        return stored;
    }

    @Override
    public StoredAudioChunk findStoredFinal(String storageKey) {
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
            if (exception.statusCode() == 403 || exception.statusCode() == 401) {
                throw new AudioStorageAccessDeniedException(exception);
            }
            if (exception.statusCode() == 404) {
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
}
