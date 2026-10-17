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
import java.util.Objects;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_audio_chunks")
public class RecordingAudioChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private long recordingId;

    @Column(nullable = false, updatable = false)
    private int sequence;

    @Column(name = "sample_offset", nullable = false, updatable = false)
    private long sampleOffset;

    @Column(name = "frame_count", nullable = false, updatable = false)
    private int frameCount;

    @Column(name = "byte_length", nullable = false, updatable = false)
    private long byteLength;

    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 44)
    private String checksumSha256;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingAudioChunkStatus status;

    @Column(name = "storage_version_id")
    private String storageVersionId;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "upload_url_expires_at", nullable = false)
    private Instant uploadUrlExpiresAt;

    protected RecordingAudioChunk() {}

    private RecordingAudioChunk(
            long recordingId,
            AudioChunkDescriptor descriptor,
            String storageKey,
            Instant expiresAt
    ) {
        if (recordingId <= 0 || descriptor == null || storageKey == null || storageKey.isBlank() || expiresAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
        this.recordingId = recordingId;
        this.sequence = descriptor.sequence();
        this.sampleOffset = descriptor.sampleOffset();
        this.frameCount = descriptor.frameCount();
        this.byteLength = descriptor.byteLength();
        this.checksumSha256 = descriptor.checksumSha256();
        this.storageKey = storageKey;
        this.status = RecordingAudioChunkStatus.RESERVED;
        this.uploadUrlExpiresAt = expiresAt;
    }

    public static RecordingAudioChunk reserve(
            long recordingId,
            AudioChunkDescriptor descriptor,
            String storageKey,
            Instant expiresAt
    ) {
        return new RecordingAudioChunk(
                recordingId,
                descriptor,
                storageKey,
                expiresAt
        );
    }

    public void validateDescriptor(AudioChunkDescriptor descriptor) {
        if (descriptor == null || sequence != descriptor.sequence() || sampleOffset != descriptor.sampleOffset()
                || frameCount != descriptor.frameCount() || byteLength != descriptor.byteLength()
                || !checksumSha256.equals(descriptor.checksumSha256())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_CONFLICT);
        }
    }

    public void extendUploadUrlExpiry(Instant expiresAt) {
        if (expiresAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        if (expiresAt.isAfter(uploadUrlExpiresAt)) {
            uploadUrlExpiresAt = expiresAt;
        }
    }

    public void verify(
            StoredAudioChunk stored,
            Instant confirmedAt
    ) {
        if (stored == null || !stored.exists()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_NOT_STORED);
        }
        if (stored.byteLength() != byteLength || !"application/octet-stream".equals(stored.contentType())
                || !checksumSha256.equals(stored.checksumSha256()) || stored.versionId() == null || stored.versionId()
                        .isBlank()
                || "null".equals(stored.versionId())
                || (status == RecordingAudioChunkStatus.VERIFIED && !Objects.equals(
                        storageVersionId,
                        stored.versionId()
                ))) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        if (confirmedAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        if (status == RecordingAudioChunkStatus.VERIFIED) {
            return;
        }
        status = RecordingAudioChunkStatus.VERIFIED;
        storageVersionId = stored.versionId();
        verifiedAt = confirmedAt;
    }
}
