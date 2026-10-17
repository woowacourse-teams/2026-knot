package com.knot.backend.recording.domain.model;
import com.knot.backend.recording.domain.AudioFinalizationLeaseLostException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
@Getter
@Entity
@Table(name = "recording_audio_finalizations")
public class RecordingAudioFinalization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private long recordingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AudioFinalizationStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextRunAt;

    @Column
    private UUID ownerToken;

    @Column(nullable = false)
    private long claimEpoch;

    @Column
    private Instant leaseUntil;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private int failureCount;

    @Column
    private String failureCode;

    @Column(nullable = false)
    private boolean retryable;

    @Column(columnDefinition = "text")
    private String manifestJson;

    @Column
    private String manifestHash;

    @Column
    private String quality;

    @Column
    private String finalKey;

    @Column
    private String finalVersionId;

    @Column
    private String finalChecksum;

    @Column
    private Long finalByteLength;

    @Column
    private Instant readyAt;

    protected RecordingAudioFinalization() {}

    public static RecordingAudioFinalization create(
            long recordingId,
            Instant now
    ) {
        if (recordingId <= 0 || now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingAudioFinalization job = new RecordingAudioFinalization();
        job.recordingId = recordingId;
        job.createdAt = now;
        job.nextRunAt = now;
        job.status = AudioFinalizationStatus.QUEUED;
        return job;
    }

    public boolean claim(
            UUID owner,
            Instant now,
            Duration lease
    ) {
        boolean queued = status == AudioFinalizationStatus.QUEUED && !now.isBefore(nextRunAt);
        boolean abandoned = status == AudioFinalizationStatus.PROCESSING && !now.isBefore(leaseUntil);
        if (!queued && !abandoned) {
            return false;
        }
        if (owner == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        ownerToken = owner;
        claimEpoch++;
        attempt++;
        leaseUntil = now.plus(lease);
        status = AudioFinalizationStatus.PROCESSING;
        failureCode = null;
        retryable = false;
        return true;
    }

    public void validateOwner(
            UUID owner,
            long epoch,
            Instant now
    ) {
        if (status != AudioFinalizationStatus.PROCESSING || !Objects.equals(
                ownerToken,
                owner
        ) || claimEpoch != epoch || leaseUntil == null || !now.isBefore(leaseUntil)) {
            throw new AudioFinalizationLeaseLostException();
        }
    }

    public void renew(
            UUID owner,
            long epoch,
            Instant now,
            Duration lease
    ) {
        validateOwner(
                owner,
                epoch,
                now
        );
        leaseUntil = now.plus(lease);
    }

    public void freeze(
            UUID owner,
            long epoch,
            Instant now,
            String manifest,
            String hash,
            String quality
    ) {
        validateOwner(
                owner,
                epoch,
                now
        );
        if (manifestJson != null) {
            validateExistingManifest(
                    manifest,
                    hash
            );
            return;
        }
        if (manifest == null || hash == null || !("COMPLETE".equals(quality) || "PARTIAL".equals(quality))) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
        manifestJson = manifest;
        manifestHash = hash;
        this.quality = quality;
        finalKey = "recordings/" + recordingId + "/final/" + id + "/" + hash + ".wav";
    }

    private void validateExistingManifest(
            String manifest,
            String hash
    ) {
        if (!manifestJson.equals(manifest) || !manifestHash.equals(hash)) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_CONFLICT);
        }
    }

    public void waitForObjects(
            UUID owner,
            long epoch,
            Instant now,
            Instant nextRunAt
    ) {
        validateOwner(
                owner,
                epoch,
                now
        );
        status = AudioFinalizationStatus.QUEUED;
        this.nextRunAt = nextRunAt;
        releaseOwner();
    }

    public void ready(
            UUID owner,
            long epoch,
            Instant now,
            StoredAudioChunk stored
    ) {
        validateOwner(
                owner,
                epoch,
                now
        );
        if (manifestJson == null || !stored.exists() || !"audio/wav".equals(stored.contentType())
                || stored.byteLength() < 44 || stored.versionId() == null || stored.versionId()
                        .isBlank()
                || "null".equals(stored.versionId()) || stored.checksumSha256() == null) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        finalVersionId = stored.versionId();
        finalChecksum = stored.checksumSha256();
        finalByteLength = stored.byteLength();
        readyAt = now;
        status = AudioFinalizationStatus.READY;
        failureCode = null;
        retryable = false;
        releaseOwner();
    }

    public void fail(
            UUID owner,
            long epoch,
            Instant now,
            String code,
            boolean transientFailure
    ) {
        validateOwner(
                owner,
                epoch,
                now
        );
        failureCount++;
        failureCode = code;
        retryable = transientFailure;
        status = AudioFinalizationStatus.FAILED;
        if (transientFailure && failureCount <= 3) {
            status = AudioFinalizationStatus.QUEUED;
            nextRunAt = now.plusSeconds(1L << (failureCount - 1));
        }
        releaseOwner();
    }

    public boolean retry(Instant now) {
        if (status == AudioFinalizationStatus.QUEUED || status == AudioFinalizationStatus.PROCESSING) {
            return false;
        }
        if (status != AudioFinalizationStatus.FAILED || !retryable) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SAVE_RETRY_NOT_ALLOWED);
        }
        claimEpoch++;
        failureCount = 0;
        failureCode = null;
        retryable = false;
        status = AudioFinalizationStatus.QUEUED;
        nextRunAt = now;
        releaseOwner();
        return true;
    }

    public void discard(Instant now) {
        status = AudioFinalizationStatus.DISCARDED;
        claimEpoch++;
        retryable = false;
        nextRunAt = now;
        releaseOwner();
    }

    public String audioSaveStatus() {
        return switch (status) {
            case QUEUED -> queuedAudioSaveStatus();
            case PROCESSING -> "FINALIZING";
            case READY -> "READY";
            case FAILED -> "FAILED";
            case DISCARDED -> "DISCARDED";
        };
    }

    private String queuedAudioSaveStatus() {
        if (manifestJson == null) {
            return "DRAINING";
        }
        return "FINALIZING";
    }

    public boolean canRetry() {
        return status == AudioFinalizationStatus.FAILED && retryable;
    }

    private void releaseOwner() {
        ownerToken = null;
        leaseUntil = null;
    }
}
