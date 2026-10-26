package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_audio_uploads")
public class RecordingAudioUpload {
    private static final String ALLOWED_CONTENT_TYPE = "audio/webm";
    private static final long MAX_CONTENT_LENGTH = 500L * 1024 * 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private Long recordingId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "content_length", nullable = false, updatable = false)
    private long contentLength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingAudioUploadStatus status;

    @Column(name = "reserved_at", nullable = false, updatable = false)
    private Instant reservedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "storage_version_id")
    private String storageVersionId;

    protected RecordingAudioUpload() {}

    private RecordingAudioUpload(
            Long recordingId,
            String storageKey,
            String contentType,
            long contentLength,
            Instant reservedAt
    ) {
        if (recordingId == null || recordingId <= 0 || storageKey == null || storageKey.isBlank()
                || reservedAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (!ALLOWED_CONTENT_TYPE.equals(contentType) || contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_UPLOAD);
        }
        this.recordingId = recordingId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.status = RecordingAudioUploadStatus.RESERVED;
        this.reservedAt = reservedAt;
    }

    public static RecordingAudioUpload reserve(
            Long recordingId,
            String storageKey,
            String contentType,
            long contentLength,
            Instant reservedAt
    ) {
        return new RecordingAudioUpload(
                recordingId,
                storageKey,
                contentType,
                contentLength,
                reservedAt
        );
    }

    public static RecordingAudioUpload adoptServerWav(
            RecordingSession session,
            String storageKey,
            StoredAudioChunk stored,
            Instant now
    ) {
        if (session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1 || session.getStatus() != RecordingStatus.ENDED
                || storageKey == null || storageKey.isBlank() || !stored.exists()
                || !"audio/wav".equals(stored.contentType()) || stored.byteLength() < 44
                || stored.byteLength() > 230400044L || stored.versionId() == null || stored.versionId()
                        .isBlank()
                || "null".equals(stored.versionId()) || now == null || now.isBefore(session.getEndedAt())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        RecordingAudioUpload upload = new RecordingAudioUpload();
        upload.recordingId = session.getId();
        upload.storageKey = storageKey;
        upload.contentType = "audio/wav";
        upload.contentLength = stored.byteLength();
        upload.reservedAt = now;
        upload.completedAt = now;
        upload.storageVersionId = stored.versionId();
        upload.status = RecordingAudioUploadStatus.COMPLETED;
        return upload;
    }

    public void validateReissuable(
            String contentType,
            long contentLength
    ) {
        if (status == RecordingAudioUploadStatus.COMPLETED) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_ALREADY_COMPLETED);
        }
        if (!this.contentType.equals(contentType) || this.contentLength != contentLength) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_UPLOAD);
        }
    }

    public void validateBelongsTo(long recordingId) {
        if (this.recordingId != recordingId) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND);
        }
    }

    public boolean isCompleted() {
        return status == RecordingAudioUploadStatus.COMPLETED;
    }

    public Optional<String> findStorageKeyToConfirm() {
        if (isCompleted()) {
            return Optional.empty();
        }
        return Optional.of(storageKey);
    }

    public boolean isAudioAvailable() {
        return isCompleted() && deletedAt == null && revokedAt == null;
    }

    // 저장소 버전이 없으면(V50 이전 완료 업로드) 같은 key의 다른 객체를 줄 수 있어 재생하지 않는다.
    public boolean isPlayable() {
        return isAudioAvailable() && storageVersionId != null && !storageVersionId.isBlank()
                && !"null".equals(storageVersionId);
    }

    public void validateAudioAvailable() {
        if (!isAudioAvailable()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        }
    }

    public void revoke(Instant revokedAt) {
        validateTimeNotBeforeReservation(revokedAt);
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
    }

    public void recordDeleted(Instant deletedAt) {
        validateTimeNotBeforeReservation(deletedAt);
        if (!isCompleted()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        }
        if (deletedAt.isBefore(completedAt)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    private void validateTimeNotBeforeReservation(Instant time) {
        if (time == null || time.isBefore(reservedAt)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
    }

    public void complete(
            StoredAudioObject storedObject,
            Instant completedAt
    ) {
        if (isCompleted()) {
            return;
        }
        if (!storedObject.matches(
                contentType,
                contentLength
        )) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        }
        validateTimeNotBeforeReservation(completedAt);
        this.status = RecordingAudioUploadStatus.COMPLETED;
        this.completedAt = completedAt;
        this.storageVersionId = storedObject.versionId();
    }
}
