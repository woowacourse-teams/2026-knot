package com.knot.backend.recording.infrastructure.storage;

import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3RecordingAudioStorage implements RecordingAudioStorage {
    private static final int NOT_FOUND_STATUS = 404;
    private static final int MAX_VERSION_PAGES = 100;
    private static final Duration DELETION_TIMEOUT = Duration.ofSeconds(60);

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;
    private final Duration uploadUrlTtl;

    public S3RecordingAudioStorage(
            S3Presigner s3Presigner,
            S3Client s3Client,
            String bucket,
            String keyPrefix,
            Duration uploadUrlTtl
    ) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = Objects.requireNonNullElse(
                keyPrefix,
                ""
        );
        this.uploadUrlTtl = uploadUrlTtl;
    }

    @Override
    public String presignDownload(
            String storageKey,
            String versionId,
            String contentType,
            long contentLength
    ) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
        try {
            String key = keyPrefix + storageKey;
            HeadObjectResponse object = s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .versionId(versionId)
                            .build()
            );
            if (object.contentLength() == null || object.contentLength() != contentLength
                    || !contentType.equals(object.contentType())) {
                throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
            }
            return s3Presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(uploadUrlTtl)
                            .getObjectRequest(
                                    GetObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(key)
                                            .versionId(versionId)
                                            .build()
                            )
                            .build()
            )
                    .url()
                    .toString();
        } catch (SdkException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    @Override
    public PresignedAudioUpload presignUpload(
            String storageKey,
            String contentType,
            long contentLength
    ) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(keyPrefix + storageKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(uploadUrlTtl)
                        .putObjectRequest(putObjectRequest)
                        .build()
        );
        return new PresignedAudioUpload(
                presigned.url()
                        .toString(),
                presigned.expiration()
        );
    }

    @Override
    public StoredAudioObject findStoredObject(String storageKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(keyPrefix + storageKey)
                            .build()
            );
            return StoredAudioObject.of(
                    response.contentLength(),
                    response.contentType(),
                    response.versionId()
            );
        } catch (SdkException exception) {
            if (exception instanceof S3Exception s3Exception && s3Exception.statusCode() == NOT_FOUND_STATUS) {
                return StoredAudioObject.missing();
            }
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    @Override
    public void deleteStoredObject(String storageKey) {
        try {
            long deadline = System.nanoTime() + DELETION_TIMEOUT.toNanos();
            String key = keyPrefix + storageKey;
            GetBucketVersioningResponse versioning = s3Client.getBucketVersioning(
                    GetBucketVersioningRequest.builder()
                            .bucket(bucket)
                            .build()
            );
            if (versioning.status() == BucketVersioningStatus.UNKNOWN_TO_SDK_VERSION) {
                throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
            }
            if (versioning.status() == BucketVersioningStatus.ENABLED
                    || versioning.status() == BucketVersioningStatus.SUSPENDED) {
                deleteVersions(
                        key,
                        deadline
                );
                return;
            }
            validateDeletionDeadline(deadline);
            deleteObject(
                    key,
                    null
            );
        } catch (SdkException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }

    private void deleteVersions(
            String key,
            long deadline
    ) {
        String keyMarker = null;
        String versionMarker = null;
        for (int page = 0; page < MAX_VERSION_PAGES; page++) {
            validateDeletionDeadline(deadline);
            ListObjectVersionsResponse versions = s3Client.listObjectVersions(
                    ListObjectVersionsRequest.builder()
                            .bucket(bucket)
                            .prefix(key)
                            .keyMarker(keyMarker)
                            .versionIdMarker(versionMarker)
                            .build()
            );
            for (ObjectVersion version : versions.versions()) {
                if (key.equals(version.key())) {
                    validateDeletionDeadline(deadline);
                    validateVersionId(version.versionId());
                    deleteObject(
                            key,
                            version.versionId()
                    );
                }
            }
            for (DeleteMarkerEntry marker : versions.deleteMarkers()) {
                if (key.equals(marker.key())) {
                    validateDeletionDeadline(deadline);
                    validateVersionId(marker.versionId());
                    deleteObject(
                            key,
                            marker.versionId()
                    );
                }
            }
            if (!Boolean.TRUE.equals(versions.isTruncated())) {
                return;
            }
            keyMarker = versions.nextKeyMarker();
            versionMarker = versions.nextVersionIdMarker();
        }
        throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
    }

    private void validateDeletionDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
    }

    private void validateVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
    }

    private void deleteObject(
            String key,
            String versionId
    ) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .versionId(versionId)
                            .build()
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() != NOT_FOUND_STATUS) {
                throw exception;
            }
        }
    }
}
