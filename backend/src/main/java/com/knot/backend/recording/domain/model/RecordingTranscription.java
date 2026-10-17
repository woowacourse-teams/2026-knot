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
import java.time.Duration;
import java.util.UUID;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_transcriptions")
public class RecordingTranscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_session_id", nullable = false, updatable = false)
    private long recordingSessionId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranscriptionStatus status;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "owner_token")
    private UUID ownerToken;

    @Column(name = "claim_epoch", nullable = false)
    private long claimEpoch;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "execution_deadline")
    private Instant executionDeadline;

    @Column(name = "readiness_started_at")
    private Instant readinessStartedAt;

    @Column(name = "readiness_failures", nullable = false)
    private int readinessFailures;

    @Enumerated(EnumType.STRING)
    @Column(name = "remote_observation", nullable = false, length = 30)
    private RemoteObservation remoteObservation;

    @Column(name = "source_storage_key")
    private String sourceStorageKey;

    @Column(name = "source_version_id")
    private String sourceVersionId;

    @Column(name = "source_content_type")
    private String sourceContentType;

    @Column(name = "source_content_length")
    private Long sourceContentLength;

    @Column(name = "speaker_count")
    private Integer speakerCount;

    protected RecordingTranscription() {}

    private RecordingTranscription(
            long recordingSessionId,
            UUID attemptId,
            Instant startedAt
    ) {
        if (recordingSessionId <= 0 || attemptId == null || startedAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        this.recordingSessionId = recordingSessionId;
        start(
                attemptId,
                startedAt
        );
    }

    public static RecordingTranscription processing(
            long recordingSessionId,
            UUID attemptId,
            Instant startedAt
    ) {
        return new RecordingTranscription(
                recordingSessionId,
                attemptId,
                startedAt
        );
    }

    public void validateRetryable() {
        if (status != TranscriptionStatus.FAILED) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED);
        }
    }

    public void retry(
            UUID nextAttemptId,
            Instant startedAt
    ) {
        validateRetryable();
        if (nextAttemptId == null || nextAttemptId.equals(attemptId) || startedAt == null
                || startedAt.isBefore(this.queuedAt)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        start(
                nextAttemptId,
                startedAt
        );
    }

    public boolean isProcessingAttempt(UUID expectedAttemptId) {
        return status == TranscriptionStatus.PROCESSING && attemptId.equals(expectedAttemptId);
    }

    public void succeed(UUID expectedAttemptId) {
        if (!isProcessingAttempt(expectedAttemptId)) {
            return;
        }
        this.status = TranscriptionStatus.SUCCEEDED;
        this.ownerToken = null;
        this.leaseUntil = null;
    }

    public void fail(
            UUID expectedAttemptId,
            String failureCode
    ) {
        if (!isProcessingAttempt(expectedAttemptId)) {
            return;
        }
        if (failureCode == null || failureCode.isBlank() || failureCode.length() > 100) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        this.status = TranscriptionStatus.FAILED;
        this.ownerToken = null;
        this.leaseUntil = null;
        this.failureCode = failureCode;
    }

    private void start(
            UUID attemptId,
            Instant startedAt
    ) {
        this.attemptId = attemptId;
        this.status = TranscriptionStatus.PROCESSING;
        this.failureCode = null;
        this.startedAt = startedAt;
        this.queuedAt = startedAt;
        this.nextRunAt = startedAt;
        this.executionDeadline = null;
        this.readinessStartedAt = null;
        this.readinessFailures = 0;
        this.remoteObservation = RemoteObservation.NOT_SUBMITTED;
        this.ownerToken = null;
        this.leaseUntil = null;
    }

    public static RecordingTranscription queued(
            long recordingId,
            UUID attemptId,
            Instant now,
            String key,
            String version,
            String type,
            long length,
            Integer speakers
    ) {
        RecordingTranscription value = processing(
                recordingId,
                attemptId,
                now
        );
        if (key == null || key.isBlank() || version == null || version.isBlank() || "null".equals(version)
                || type == null || type.isBlank() || length <= 0 || (speakers != null && speakers <= 0)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        value.sourceStorageKey = key;
        value.sourceVersionId = version;
        value.sourceContentType = type;
        value.sourceContentLength = length;
        value.speakerCount = speakers;
        value.status = TranscriptionStatus.QUEUED;
        value.startedAt = null;
        return value;
    }

    public void enqueueRetry(
            UUID nextAttemptId,
            Instant now
    ) {
        retry(
                nextAttemptId,
                now
        );
        status = TranscriptionStatus.QUEUED;
        startedAt = null;
    }

    public void claim(
            UUID owner,
            Instant now,
            Duration lease
    ) {
        if (owner == null || now == null || lease == null || lease.isNegative() || lease.isZero()
                || (status != TranscriptionStatus.QUEUED && status != TranscriptionStatus.PROCESSING)
                || (status == TranscriptionStatus.PROCESSING && leaseUntil != null && now.isBefore(leaseUntil))
                || now.isBefore(nextRunAt)) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_BUSY);
        }
        status = TranscriptionStatus.PROCESSING;
        if (startedAt == null) {
            startedAt = now;
        }
        ownerToken = owner;
        claimEpoch++;
        leaseUntil = now.plus(lease);
    }

    public boolean isOwnedBy(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now
    ) {
        return isProcessingAttempt(attempt) && owner != null && owner.equals(ownerToken) && epoch == claimEpoch
                && leaseUntil != null && now.isBefore(leaseUntil);
    }

    public void renew(
            Instant now,
            Duration lease
    ) {
        validateExecutionTime(
                now,
                lease
        );
        leaseUntil = now.plus(lease);
    }

    public void beginRemotePost(
            Instant now,
            Duration timeout
    ) {
        validateExecutionTime(
                now,
                timeout
        );
        if (executionDeadline == null) {
            executionDeadline = now.plus(timeout);
        }
        remoteObservation = RemoteObservation.UNCONFIRMED;
    }

    public void restoreLegacyDeadline(Duration timeout) {
        if (executionDeadline == null && remoteObservation == RemoteObservation.UNCONFIRMED && startedAt != null) {
            executionDeadline = startedAt.plus(timeout);
        }
    }

    public void observe(RemoteObservation observation) {
        if (observation == null || status != TranscriptionStatus.PROCESSING) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        remoteObservation = observation;
    }

    public void requeue(Instant nextRun) {
        if (nextRun == null || nextRun.isBefore(queuedAt) || status != TranscriptionStatus.PROCESSING) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        status = TranscriptionStatus.QUEUED;
        nextRunAt = nextRun;
        ownerToken = null;
        leaseUntil = null;
    }

    public boolean deferReadiness(
            Instant now,
            Duration maximumWait
    ) {
        if (readinessStartedAt == null) {
            readinessStartedAt = now;
        }
        if (!now.isBefore(readinessStartedAt.plus(maximumWait))) {
            return false;
        }
        if (remoteObservation == RemoteObservation.NOT_ACCEPTED) {
            executionDeadline = null;
        }
        long delay = Math.min(
                30L,
                1L << Math.min(
                        readinessFailures++,
                        5
                )
        );
        requeue(now.plusSeconds(delay));
        return true;
    }

    public boolean isOutcomeUncertain() {
        return status == TranscriptionStatus.FAILED && "AI_OUTCOME_UNKNOWN".equals(failureCode);
    }

    public void resumeUncertain(Instant now) {
        if (!isOutcomeUncertain() || now == null || now.isBefore(queuedAt)) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_RETRY_NOT_ALLOWED);
        }
        status = TranscriptionStatus.QUEUED;
        failureCode = null;
        nextRunAt = now;
        ownerToken = null;
        leaseUntil = null;
    }
    private void validateExecutionTime(
            Instant now,
            Duration duration
    ) {
        if (status != TranscriptionStatus.PROCESSING || now == null || now.isBefore(queuedAt) || duration == null
                || duration.isNegative() || duration.isZero()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

}
