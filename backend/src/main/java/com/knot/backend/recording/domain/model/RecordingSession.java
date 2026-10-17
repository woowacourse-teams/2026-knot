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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_sessions")
public class RecordingSession {
    public static final long MAX_RECORDING_MILLIS = Duration.ofHours(2)
            .toMillis();
    private static final Pattern SHA_256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "tab_id", nullable = false)
    private UUID tabId;

    @Getter(AccessLevel.NONE)
    @Column(name = "control_token_hash", nullable = false, length = 64)
    private String controlTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_mode", nullable = false, updatable = false, length = 30)
    private RecordingAudioMode audioMode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Getter(AccessLevel.NONE)
    @Column(name = "current_interval_started_at")
    private Instant currentIntervalStartedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private RecordingEndReason endReason;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "accumulated_recording_millis", nullable = false)
    private long accumulatedRecordingMillis;

    protected RecordingSession() {}

    private RecordingSession(
            Long workspaceId,
            Long memberId,
            UUID requestId,
            UUID tabId,
            String controlTokenHash,
            Instant startedAt
    ) {
        validateStart(
                workspaceId,
                memberId,
                requestId,
                tabId,
                controlTokenHash,
                startedAt
        );
        this.workspaceId = workspaceId;
        this.memberId = memberId;
        this.requestId = requestId;
        this.tabId = tabId;
        this.controlTokenHash = controlTokenHash;
        this.audioMode = RecordingAudioMode.FINAL_WEBM_V1;
        this.status = RecordingStatus.RECORDING;
        this.startedAt = startedAt;
        this.currentIntervalStartedAt = startedAt;
        this.lastSeenAt = startedAt;
        this.accumulatedRecordingMillis = 0L;
    }

    public static Instant disconnectionThreshold(Instant now) {
        return now.minus(CONNECTION_TIMEOUT);
    }

    public static RecordingSession start(
            Long workspaceId,
            Long memberId,
            UUID requestId,
            UUID tabId,
            String controlTokenHash,
            Instant startedAt
    ) {
        return new RecordingSession(
                workspaceId,
                memberId,
                requestId,
                tabId,
                controlTokenHash,
                startedAt
        );
    }

    public static RecordingSession start(
            Long workspaceId,
            Long memberId,
            UUID requestId,
            UUID tabId,
            String controlTokenHash,
            Instant startedAt,
            RecordingAudioMode audioMode
    ) {
        if (audioMode == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingSession session = start(
                workspaceId,
                memberId,
                requestId,
                tabId,
                controlTokenHash,
                startedAt
        );
        session.audioMode = audioMode;
        return session;
    }

    public void validateStartRetry(
            long workspaceId,
            UUID tabId,
            String controlTokenHash,
            RecordingAudioMode audioMode
    ) {
        validateStartRetryIdentity(
                workspaceId,
                tabId,
                controlTokenHash
        );
        if (this.audioMode != audioMode) {
            throw new RecordingException(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        }
    }

    public void validateLegacyAudioMode() {
        if (audioMode != RecordingAudioMode.FINAL_WEBM_V1) {
            throw new RecordingException(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        }
    }

    public void validateStartRetry(
            long workspaceId,
            UUID tabId,
            String controlTokenHash
    ) {
        validateStartRetry(
                workspaceId,
                tabId,
                controlTokenHash,
                RecordingAudioMode.FINAL_WEBM_V1
        );
    }

    public void releaseForNewRecording(Instant now) {
        if (!expireIfDue(now)) {
            throw new RecordingException(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
        }
    }

    public void validateControlledBy(
            long workspaceId,
            long memberId
    ) {
        if (this.workspaceId != workspaceId) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
        if (this.memberId != memberId) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
    }

    public void validateControlProof(
            UUID tabId,
            String controlTokenHash
    ) {
        if (!this.tabId.equals(tabId) || !sameControlTokenHash(controlTokenHash)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
    }

    public boolean belongsTo(long workspaceId) {
        return this.workspaceId == workspaceId;
    }

    public boolean isTranscribableIn(long workspaceId) {
        return belongsTo(workspaceId) && status == RecordingStatus.ENDED;
    }

    public void validateAudioUploadable() {
        validateEnded();
    }

    public void validateEnded() {
        ensureNotDiscarded();
        if (status != RecordingStatus.ENDED) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_ENDED);
        }
    }

    public void pause(Instant pausedAt) {
        ensureActive();
        endIfExpired(pausedAt);
        if (status == RecordingStatus.PAUSED) {
            return;
        }
        validateTimeNotBeforeLastSeen(pausedAt);
        validateTimeNotBeforeCurrentInterval(pausedAt);
        accumulatedRecordingMillis += Duration.between(
                currentIntervalStartedAt,
                pausedAt
        )
                .toMillis();
        status = RecordingStatus.PAUSED;
        currentIntervalStartedAt = null;
        this.pausedAt = pausedAt;
        lastSeenAt = pausedAt;
    }

    public void resume(Instant resumedAt) {
        ensureActive();
        endIfExpired(resumedAt);
        if (status == RecordingStatus.RECORDING) {
            return;
        }
        validateTimeNotBeforeLastSeen(resumedAt);
        status = RecordingStatus.RECORDING;
        currentIntervalStartedAt = resumedAt;
        pausedAt = null;
        lastSeenAt = resumedAt;
    }

    public Instant getResumedAt() {
        if (status != RecordingStatus.RECORDING) {
            return null;
        }
        return currentIntervalStartedAt;
    }

    public void end(Instant endedAt) {
        if (status == RecordingStatus.ENDED) {
            return;
        }
        ensureNotDiscarded();
        if (expireIfDue(endedAt)) {
            return;
        }
        stop(
                RecordingStatus.ENDED,
                endedAt
        );
        endReason = RecordingEndReason.USER_ENDED;
    }

    public boolean expireIfDue(Instant now) {
        if (!isActive() || !isDue(now)) {
            return false;
        }
        RecordingEndReason reason = calculateDueReason();
        Instant expiredAt = calculateDueAt();
        if (status == RecordingStatus.RECORDING) {
            accumulatedRecordingMillis += Duration.between(
                    currentIntervalStartedAt,
                    expiredAt
            )
                    .toMillis();
        }
        status = RecordingStatus.ENDED;
        currentIntervalStartedAt = null;
        endedAt = expiredAt;
        endReason = reason;
        return true;
    }

    public void recordHeartbeat(Instant receivedAt) {
        if (!isActive() || expireIfDue(receivedAt)) {
            return;
        }
        if (receivedAt.isAfter(lastSeenAt)) {
            lastSeenAt = receivedAt;
        }
    }

    public boolean isActive() {
        return RecordingStatus.ACTIVE_STATUSES.contains(status);
    }

    public Instant getConnectionExpiresAt() {
        if (!isActive()) {
            return null;
        }
        return lastSeenAt.plus(CONNECTION_TIMEOUT);
    }

    public void discard(Instant discardedAt) {
        if (status == RecordingStatus.DISCARDED) {
            return;
        }
        ensureNotEnded();
        stop(
                RecordingStatus.DISCARDED,
                notBeforeLastSeen(discardedAt)
        );
    }

    public long getRecordingDurationMillis(Instant now) {
        if (status != RecordingStatus.RECORDING) {
            return accumulatedRecordingMillis;
        }
        validateTimeNotBeforeCurrentInterval(now);
        return Math.min(
                accumulatedRecordingMillis + Duration.between(
                        currentIntervalStartedAt,
                        now
                )
                        .toMillis(),
                MAX_RECORDING_MILLIS
        );
    }

    private void validateStartRetryIdentity(
            long workspaceId,
            UUID tabId,
            String controlTokenHash
    ) {
        if (this.workspaceId != workspaceId || !this.tabId.equals(tabId) || !sameControlTokenHash(controlTokenHash)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_START_REQUEST_CONFLICT);
        }
    }

    private boolean sameControlTokenHash(String controlTokenHash) {
        if (!isValidControlTokenHash(controlTokenHash)) {
            return false;
        }
        return MessageDigest.isEqual(
                this.controlTokenHash.getBytes(StandardCharsets.US_ASCII),
                controlTokenHash.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private void stop(
            RecordingStatus stoppedStatus,
            Instant stoppedAt
    ) {
        validateTimeNotBeforeLastSeen(stoppedAt);
        if (status == RecordingStatus.RECORDING) {
            validateTimeNotBeforeCurrentInterval(stoppedAt);
            accumulatedRecordingMillis += Duration.between(
                    currentIntervalStartedAt,
                    stoppedAt
            )
                    .toMillis();
        }
        status = stoppedStatus;
        currentIntervalStartedAt = null;
        this.endedAt = stoppedAt;
        lastSeenAt = stoppedAt;
    }

    private Instant notBeforeLastSeen(Instant pointInTime) {
        if (pointInTime != null && pointInTime.isBefore(lastSeenAt)) {
            return lastSeenAt;
        }
        return pointInTime;
    }

    private boolean isDue(Instant now) {
        if (now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        return !now.isBefore(calculateDueAt());
    }

    private Instant calculateDueAt() {
        if (calculateDueReason() == RecordingEndReason.MAX_DURATION) {
            return calculateMaxDurationReachedAt();
        }
        return lastSeenAt.plus(CONNECTION_TIMEOUT);
    }

    private RecordingEndReason calculateDueReason() {
        Instant maxDurationReachedAt = calculateMaxDurationReachedAt();
        if (maxDurationReachedAt != null && !maxDurationReachedAt.isAfter(lastSeenAt.plus(CONNECTION_TIMEOUT))) {
            return RecordingEndReason.MAX_DURATION;
        }
        return RecordingEndReason.CONNECTION_EXPIRED;
    }

    private Instant calculateMaxDurationReachedAt() {
        if (status != RecordingStatus.RECORDING) {
            return null;
        }
        return currentIntervalStartedAt.plusMillis(MAX_RECORDING_MILLIS - accumulatedRecordingMillis);
    }

    private void endIfExpired(Instant now) {
        if (expireIfDue(now)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        }
    }

    private void ensureActive() {
        ensureNotEnded();
        ensureNotDiscarded();
    }

    private void ensureNotEnded() {
        if (status == RecordingStatus.ENDED) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        }
    }

    private void ensureNotDiscarded() {
        if (status == RecordingStatus.DISCARDED) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_DISCARDED);
        }
    }

    private void validateTimeNotBeforeCurrentInterval(Instant pointInTime) {
        if (pointInTime == null || pointInTime.isBefore(currentIntervalStartedAt)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
    }

    private void validateTimeNotBeforeLastSeen(Instant pointInTime) {
        if (pointInTime == null || pointInTime.isBefore(lastSeenAt)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
    }

    private void validateStart(
            Long workspaceId,
            Long memberId,
            UUID requestId,
            UUID tabId,
            String controlTokenHash,
            Instant startedAt
    ) {
        if (workspaceId == null || workspaceId <= 0 || memberId == null || memberId <= 0 || requestId == null
                || tabId == null || !isValidControlTokenHash(controlTokenHash)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (startedAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
    }

    private static boolean isValidControlTokenHash(String controlTokenHash) {
        return controlTokenHash != null && SHA_256_HEX_PATTERN.matcher(controlTokenHash)
                .matches();
    }

}
