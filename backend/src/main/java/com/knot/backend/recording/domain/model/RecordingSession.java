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

    @Column(length = 64, updatable = false)
    private String incrementalPipelineVersion;

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

    public void enableIncrementalTranscription(String pipelineVersion) {
        if (id != null || audioMode != RecordingAudioMode.PCM16_CHUNKS_V1 || pipelineVersion == null
                || pipelineVersion.isBlank() || pipelineVersion.length() > 64 || incrementalPipelineVersion != null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        incrementalPipelineVersion = pipelineVersion;
    }

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

    public void releaseForNewRecording(
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (!expireIfDue(
                now,
                connectionPolicy
        )) {
            throw new RecordingException(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
        }
    }

    public void validateControlledBy(
            long workspaceId,
            long memberId
    ) {
        validateBelongsTo(workspaceId);
        if (this.memberId != memberId) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
    }

    public void validateControlProof(
            UUID tabId,
            String controlTokenHash
    ) {
        if (!isControlledBy(
                tabId,
                controlTokenHash
        )) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
    }

    /**
     * 같은 탭 새로고침 뒤 이어 받기다. 이전 증명이 현재 녹음 탭 증명이면 새 증명으로 바꾸고, 새 증명이 이미 현재 증명이면 같은 이어
     * 받기의 재시도로 본다. 끝난 녹음은 증명을 바꾸지 않는다.
     *
     * @return 이번 요청으로 녹음 탭 증명을 바꿨으면 true
     */
    public boolean reconnect(
            UUID previousTabId,
            String previousControlTokenHash,
            UUID tabId,
            String controlTokenHash,
            Instant reconnectedAt,
            RecordingConnectionPolicy connectionPolicy
    ) {
        validateReplacementProof(
                previousTabId,
                previousControlTokenHash,
                tabId,
                controlTokenHash
        );
        boolean handedOver = isControlledBy(
                previousTabId,
                previousControlTokenHash
        );
        boolean retried = isControlledBy(
                tabId,
                controlTokenHash
        );
        if (!handedOver && !retried) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
        ensureNotDiscarded();
        expireIfDue(
                reconnectedAt,
                connectionPolicy
        );
        if (!isActive()) {
            return false;
        }
        if (handedOver) {
            this.tabId = tabId;
            this.controlTokenHash = controlTokenHash;
        }
        recordAlive(
                reconnectedAt,
                connectionPolicy
        );
        return handedOver;
    }

    public boolean belongsTo(long workspaceId) {
        return this.workspaceId == workspaceId;
    }

    public void validateBelongsTo(long workspaceId) {
        if (!belongsTo(workspaceId)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
    }

    public boolean isTranscribableIn(long workspaceId) {
        return belongsTo(workspaceId) && status == RecordingStatus.ENDED;
    }

    public void validateNotDiscarded() {
        ensureNotDiscarded();
    }

    public void validateEnded() {
        ensureNotDiscarded();
        if (status != RecordingStatus.ENDED) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_ENDED);
        }
    }

    public void pause(
            Instant pausedAt,
            RecordingConnectionPolicy connectionPolicy
    ) {
        ensureActive();
        endIfExpired(
                pausedAt,
                connectionPolicy
        );
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

    public void resume(
            Instant resumedAt,
            RecordingConnectionPolicy connectionPolicy
    ) {
        ensureActive();
        endIfExpired(
                resumedAt,
                connectionPolicy
        );
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

    public void end(
            Instant endedAt,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (status == RecordingStatus.ENDED) {
            return;
        }
        ensureNotDiscarded();
        if (expireIfDue(
                endedAt,
                connectionPolicy
        )) {
            return;
        }
        stop(
                RecordingStatus.ENDED,
                endedAt
        );
        endReason = RecordingEndReason.USER_ENDED;
    }

    /**
     * 끊김 유예나 2시간 상한이 지났으면 끝낸다. 연결 만료의 끝난 시각은 마지막 생존 시각이고 기다린 유예는 녹음 시간에 넣지 않는다.
     */
    public boolean expireIfDue(
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (!isActive() || !isDue(
                now,
                connectionPolicy
        )) {
            return false;
        }
        RecordingEndReason reason = calculateDueReason(connectionPolicy);
        Instant expiredAt = calculateAutoEndedAt(reason);
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

    /** 녹음 탭의 생존 신호를 접수한다. 늦게 도착한 요청은 마지막 생존 시각을 되돌리지 않는다. */
    public void recordAlive(
            Instant receivedAt,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (!isActive() || expireIfDue(
                receivedAt,
                connectionPolicy
        )) {
            return;
        }
        if (receivedAt.isAfter(lastSeenAt)) {
            lastSeenAt = receivedAt;
        }
    }

    public boolean isActive() {
        return RecordingStatus.ACTIVE_STATUSES.contains(status);
    }

    public Instant connectionExpiresAt(RecordingConnectionPolicy connectionPolicy) {
        if (!isActive()) {
            return null;
        }
        return connectionPolicy.expiresAt(lastSeenAt);
    }

    public RecordingConnectionStatus connectionStatusAt(
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (!isActive()) {
            return null;
        }
        return connectionPolicy.statusAt(
                lastSeenAt,
                now
        );
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

    private boolean isControlledBy(
            UUID tabId,
            String controlTokenHash
    ) {
        return this.tabId.equals(tabId) && sameControlTokenHash(controlTokenHash);
    }

    private void validateReplacementProof(
            UUID previousTabId,
            String previousControlTokenHash,
            UUID tabId,
            String controlTokenHash
    ) {
        if (previousTabId == null || tabId == null || !isValidControlTokenHash(controlTokenHash)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (previousTabId.equals(tabId) || controlTokenHash.equals(previousControlTokenHash)) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
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

    private boolean isDue(
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_TIME);
        }
        return !now.isBefore(calculateDueAt(connectionPolicy));
    }

    private Instant calculateDueAt(RecordingConnectionPolicy connectionPolicy) {
        if (calculateDueReason(connectionPolicy) == RecordingEndReason.MAX_DURATION) {
            return calculateMaxDurationReachedAt();
        }
        return connectionPolicy.expiresAt(lastSeenAt);
    }

    private Instant calculateAutoEndedAt(RecordingEndReason reason) {
        if (reason == RecordingEndReason.MAX_DURATION) {
            return calculateMaxDurationReachedAt();
        }
        return lastSeenAt;
    }

    private RecordingEndReason calculateDueReason(RecordingConnectionPolicy connectionPolicy) {
        Instant maxDurationReachedAt = calculateMaxDurationReachedAt();
        if (maxDurationReachedAt != null && !maxDurationReachedAt.isAfter(connectionPolicy.expiresAt(lastSeenAt))) {
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

    private void endIfExpired(
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        if (expireIfDue(
                now,
                connectionPolicy
        )) {
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
