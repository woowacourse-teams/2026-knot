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
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_transcription_windows")
public class RecordingTranscriptionWindow {
    private static final long MAX_FRAME = 115_200_000;
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int MAX_PIPELINE_VERSION_LENGTH = 64;
    private static final int MAX_FAILURE_CODE_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private long recordingId;

    @Column(nullable = false, updatable = false, length = 64)
    private String pipelineVersion;

    @Column(nullable = false, updatable = false)
    private int windowIndex;

    @Column(nullable = false, updatable = false)
    private long coreStartFrame;

    @Column(nullable = false, updatable = false)
    private long coreEndFrame;

    @Column(nullable = false, updatable = false)
    private long inputStartFrame;

    @Column(nullable = false, updatable = false)
    private long inputEndFrame;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String manifestJson;

    @Column(nullable = false, updatable = false, length = 64)
    private String manifestSha256;

    /** 2분 구간에서만 정한다. 30초 구간은 null이며 AI 요청에서 빠진다. */
    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 20)
    private TranscriptionDecodePolicy decodePolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranscriptionWindowStatus status;

    @Column(nullable = false)
    private UUID attemptId;

    @Column
    private UUID ownerToken;

    @Column(nullable = false)
    private long claimEpoch;

    @Column
    private Instant leaseUntil;

    @Column(nullable = false)
    private Instant nextRunAt;

    @Column
    private Instant executionDeadline;

    @Column
    private Instant lastClaimedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 100)
    private String failureCode;

    @Column
    private Integer resultSchemaVersion;

    @Column(columnDefinition = "text")
    private String resultJson;

    @Column(length = 64)
    private String resultSha256;

    protected RecordingTranscriptionWindow() {}

    public static RecordingTranscriptionWindow queued(
            long recordingId,
            String pipelineVersion,
            int windowIndex,
            long coreStartFrame,
            long coreEndFrame,
            long inputStartFrame,
            long inputEndFrame,
            String manifestJson,
            UUID attemptId,
            Instant now
    ) {
        return queued(
                recordingId,
                pipelineVersion,
                windowIndex,
                coreStartFrame,
                coreEndFrame,
                inputStartFrame,
                inputEndFrame,
                manifestJson,
                null,
                attemptId,
                now
        );
    }

    public static RecordingTranscriptionWindow queued(
            long recordingId,
            String pipelineVersion,
            int windowIndex,
            long coreStartFrame,
            long coreEndFrame,
            long inputStartFrame,
            long inputEndFrame,
            String manifestJson,
            TranscriptionDecodePolicy decodePolicy,
            UUID attemptId,
            Instant now
    ) {
        if (recordingId <= 0 || pipelineVersion == null || pipelineVersion.isBlank()
                || pipelineVersion.length() > MAX_PIPELINE_VERSION_LENGTH || windowIndex < 0 || inputStartFrame < 0
                || inputStartFrame > coreStartFrame || coreStartFrame >= coreEndFrame || coreEndFrame > inputEndFrame
                || inputEndFrame > MAX_FRAME || attemptId == null || now == null) {
            throw invalidData();
        }
        String manifestSha256 = jsonSha256(manifestJson);
        RecordingTranscriptionWindow window = new RecordingTranscriptionWindow();
        window.recordingId = recordingId;
        window.pipelineVersion = pipelineVersion;
        window.windowIndex = windowIndex;
        window.coreStartFrame = coreStartFrame;
        window.coreEndFrame = coreEndFrame;
        window.inputStartFrame = inputStartFrame;
        window.inputEndFrame = inputEndFrame;
        window.manifestJson = manifestJson;
        window.manifestSha256 = manifestSha256;
        window.decodePolicy = decodePolicy;
        window.attemptId = attemptId;
        window.createdAt = now;
        window.nextRunAt = now;
        window.status = TranscriptionWindowStatus.QUEUED;
        return window;
    }

    public boolean claim(
            UUID owner,
            Instant now,
            Duration lease,
            Duration executionTimeout
    ) {
        if (owner == null || now == null) {
            throw invalidData();
        }
        validateDuration(lease);
        validateDuration(executionTimeout);
        boolean queued = status == TranscriptionWindowStatus.QUEUED && !now.isBefore(nextRunAt);
        boolean abandoned = status == TranscriptionWindowStatus.PROCESSING && leaseUntil != null
                && !now.isBefore(leaseUntil);
        if ((!queued && !abandoned) || (executionDeadline != null && !now.isBefore(executionDeadline))) {
            return false;
        }
        Instant deadline = executionDeadline == null
                ? plus(
                        now,
                        executionTimeout
                )
                : executionDeadline;
        Instant expiry = boundedLease(
                now,
                lease,
                deadline
        );
        if (claimEpoch == Long.MAX_VALUE) {
            throw invalidData();
        }
        executionDeadline = deadline;
        lastClaimedAt = now;
        ownerToken = owner;
        claimEpoch++;
        leaseUntil = expiry;
        status = TranscriptionWindowStatus.PROCESSING;
        failureCode = null;
        return true;
    }

    public boolean renew(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now,
            Duration lease
    ) {
        if (!isOwnedBy(
                attempt,
                owner,
                epoch,
                now
        )) {
            return false;
        }
        validateDuration(lease);
        leaseUntil = boundedLease(
                now,
                lease,
                executionDeadline
        );
        return true;
    }

    public boolean succeed(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now,
            int schemaVersion,
            String resultJson
    ) {
        if (!isOwnedBy(
                attempt,
                owner,
                epoch,
                now
        )) {
            return false;
        }
        if (schemaVersion <= 0) {
            throw invalidData();
        }
        String resultSha256 = jsonSha256(resultJson);
        this.resultSchemaVersion = schemaVersion;
        this.resultJson = resultJson;
        this.resultSha256 = resultSha256;
        status = TranscriptionWindowStatus.SUCCEEDED;
        failureCode = null;
        releaseOwner();
        return true;
    }

    public boolean defer(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now,
            Instant nextRunAt,
            String failureCode
    ) {
        if (!isOwnedBy(
                attempt,
                owner,
                epoch,
                now
        )) {
            return false;
        }
        if (nextRunAt == null || nextRunAt.isBefore(now)) {
            throw invalidData();
        }
        validateFailureCode(failureCode);
        this.nextRunAt = nextRunAt;
        this.failureCode = failureCode;
        status = TranscriptionWindowStatus.QUEUED;
        releaseOwner();
        return true;
    }

    public boolean fail(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now,
            String failureCode
    ) {
        if (!isOwnedBy(
                attempt,
                owner,
                epoch,
                now
        )) {
            return false;
        }
        validateFailureCode(failureCode);
        this.failureCode = failureCode;
        status = TranscriptionWindowStatus.FAILED;
        releaseOwner();
        return true;
    }

    public boolean expire(Instant now) {
        if (now == null) {
            throw invalidData();
        }
        boolean active = status == TranscriptionWindowStatus.QUEUED || status == TranscriptionWindowStatus.PROCESSING;
        if (!active || executionDeadline == null || now.isBefore(executionDeadline)) {
            return false;
        }
        status = TranscriptionWindowStatus.FAILED;
        failureCode = "EXECUTION_DEADLINE_EXCEEDED";
        releaseOwner();
        return true;
    }

    public boolean resetForExplicitRetry(
            UUID nextAttemptId,
            Instant now,
            boolean regenerateSucceeded
    ) {
        if (nextAttemptId == null || nextAttemptId.equals(attemptId) || now == null || now.isBefore(createdAt)) {
            throw invalidData();
        }
        if (status != TranscriptionWindowStatus.FAILED
                && !(regenerateSucceeded && status == TranscriptionWindowStatus.SUCCEEDED)) {
            return false;
        }
        attemptId = nextAttemptId;
        nextRunAt = now;
        executionDeadline = null;
        status = TranscriptionWindowStatus.QUEUED;
        failureCode = null;
        resultSchemaVersion = null;
        resultJson = null;
        resultSha256 = null;
        releaseOwner();
        return true;
    }

    public void cancel() {
        status = TranscriptionWindowStatus.CANCELLED;
        resultSchemaVersion = null;
        resultJson = null;
        resultSha256 = null;
        failureCode = null;
        releaseOwner();
    }

    public boolean isOwnedBy(
            UUID attempt,
            UUID owner,
            long epoch,
            Instant now
    ) {
        return status == TranscriptionWindowStatus.PROCESSING && attempt != null && owner != null && now != null
                && Objects.equals(
                        attemptId,
                        attempt
                ) && Objects.equals(
                        ownerToken,
                        owner
                ) && claimEpoch == epoch && leaseUntil != null && now.isBefore(leaseUntil) && executionDeadline != null
                && now.isBefore(executionDeadline);
    }

    private void releaseOwner() {
        ownerToken = null;
        leaseUntil = null;
    }

    private static String jsonSha256(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_BYTES) {
            throw invalidData();
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw invalidData();
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(bytes)
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private static void validateDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw invalidData();
        }
    }

    private static void validateFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank() || failureCode.length() > MAX_FAILURE_CODE_LENGTH) {
            throw invalidData();
        }
    }

    private static Instant boundedLease(
            Instant now,
            Duration lease,
            Instant deadline
    ) {
        Instant expiry = plus(
                now,
                lease
        );
        return expiry.isBefore(deadline) ? expiry : deadline;
    }

    private static Instant plus(
            Instant now,
            Duration duration
    ) {
        try {
            return now.plus(duration);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private static RecordingException invalidData() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
    }
}
