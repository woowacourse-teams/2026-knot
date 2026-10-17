package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_audio_cleanup")
public class RecordingAudioCleanup {
    @Id
    @Column(name = "recording_id", updatable = false)
    private long recordingId;

    @Column(name = "storage_prefix", nullable = false, updatable = false)
    private String storagePrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecordingAudioCleanupScope scope;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "max_url_expires_at")
    private Instant maxUrlExpiresAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_epoch", nullable = false)
    private long claimEpoch;

    @Column(name = "last_swept_at")
    private Instant lastSweptAt;

    @Column(name = "last_failure_code")
    private String lastFailureCode;

    protected RecordingAudioCleanup() {}

    public static RecordingAudioCleanup track(
            long recordingId,
            Instant now
    ) {
        if (recordingId <= 0 || now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingAudioCleanup value = new RecordingAudioCleanup();
        value.recordingId = recordingId;
        value.storagePrefix = "recordings/" + recordingId + "/";
        value.scope = RecordingAudioCleanupScope.DORMANT;
        value.createdAt = now;
        return value;
    }

    public void request(
            RecordingAudioCleanupScope requestedScope,
            Instant expiresAt,
            Instant now
    ) {
        if (requestedScope == null || requestedScope == RecordingAudioCleanupScope.DORMANT || now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (expiresAt != null && (maxUrlExpiresAt == null || expiresAt.isAfter(maxUrlExpiresAt))) {
            maxUrlExpiresAt = expiresAt;
        }
        if (scope == RecordingAudioCleanupScope.ALL || scope == requestedScope) {
            return;
        }
        scope = requestedScope;
        requestedAt = now;
        nextAttemptAt = now;
        leaseUntil = null;
        claimEpoch++;
    }

    public boolean claim(
            Instant now,
            Duration lease
    ) {
        if (now == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        if (scope == RecordingAudioCleanupScope.DORMANT || nextAttemptAt == null || now.isBefore(nextAttemptAt)
                || (leaseUntil != null && now.isBefore(leaseUntil))) {
            return false;
        }
        claimEpoch++;
        leaseUntil = now.plus(lease);
        return true;
    }

    public String deletionPrefix() {
        if (scope == RecordingAudioCleanupScope.DORMANT) {
            throw new RecordingException(RecordingErrorCode.AUDIO_DELETION_CONFLICT);
        }
        if (scope == RecordingAudioCleanupScope.RAW) {
            return storagePrefix + "chunks/";
        }
        return storagePrefix;
    }

    public void finish(
            long epoch,
            boolean success,
            Instant now
    ) {
        if (epoch != claimEpoch || leaseUntil == null || !now.isBefore(leaseUntil)) {
            return;
        }
        leaseUntil = null;
        recordOutcome(success);
        if (success) {
            lastSweptAt = now;
        }
        // PUT 시작 시각만으로 완료 시각을 증명할 수 없으므로 tombstone은 성공 후에도 유지한다.
        nextAttemptAt = now.plus(
                nextSweepDelay(
                        success,
                        now
                )
        );
    }

    private void recordOutcome(boolean success) {
        if (success) {
            lastFailureCode = null;
            return;
        }
        lastFailureCode = RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE.getCode();
    }

    private Duration nextSweepDelay(
            boolean success,
            Instant now
    ) {
        if (!success || (maxUrlExpiresAt != null && now.isBefore(maxUrlExpiresAt))) {
            return Duration.ofMinutes(1);
        }
        return Duration.ofHours(1);
    }
}
