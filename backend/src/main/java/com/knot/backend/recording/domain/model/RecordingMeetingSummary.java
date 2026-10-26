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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_meeting_summaries")
public class RecordingMeetingSummary {
    private static final int MAX_AUTOMATIC_RETRIES = 1;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private long recordingId;

    @Column(name = "transcript_id", nullable = false, updatable = false)
    private long transcriptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingSummaryStatus status;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "attempt_no", nullable = false)
    private long attemptNo;

    @Column(name = "user_retry_count", nullable = false)
    private long userRetryCount;

    @Column(name = "automatic_retry_count", nullable = false)
    private int automaticRetryCount;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RecordingMeetingSummary() {}

    public static RecordingMeetingSummary queue(
            long recordingId,
            long transcriptId,
            Instant now
    ) {
        if (recordingId <= 0 || transcriptId <= 0 || now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingMeetingSummary summary = new RecordingMeetingSummary();
        summary.recordingId = recordingId;
        summary.transcriptId = transcriptId;
        summary.status = MeetingSummaryStatus.QUEUED;
        summary.nextRunAt = now;
        summary.createdAt = now;
        return summary;
    }

    public Optional<UUID> claim(
            Instant now,
            Duration lease
    ) {
        if (isExpired(now)) {
            recoverExpiredLease(now);
        }
        if (status != MeetingSummaryStatus.QUEUED || now.isBefore(nextRunAt)) {
            return Optional.empty();
        }
        status = MeetingSummaryStatus.RUNNING;
        attemptNo++;
        claimToken = UUID.randomUUID();
        leaseUntil = now.plus(lease);
        startedAt = now;
        completedAt = null;
        return Optional.of(claimToken);
    }

    public boolean succeed(
            UUID token,
            String generated,
            Instant now
    ) {
        if (!owns(
                token,
                now
        )) {
            return false;
        }
        content = MeetingSummaryContent.normalize(generated);
        finish(
                MeetingSummaryStatus.SUCCEEDED,
                null,
                now
        );
        return true;
    }

    public boolean fail(
            UUID token,
            String code,
            boolean transientFailure,
            Instant now
    ) {
        if (!owns(
                token,
                now
        )) {
            return false;
        }
        if (transientFailure && automaticRetryCount < MAX_AUTOMATIC_RETRIES) {
            automaticRetryCount++;
            enqueue(now.plus(RETRY_BACKOFF));
            failureCode = code;
            return true;
        }
        finish(
                MeetingSummaryStatus.FAILED,
                code,
                now
        );
        return true;
    }

    public boolean canRetry(boolean usableInput) {
        return status == MeetingSummaryStatus.FAILED && usableInput;
    }

    public void retry(
            boolean usableInput,
            Instant now
    ) {
        if (!canRetry(usableInput)) {
            throw new RecordingException(RecordingErrorCode.MEETING_SUMMARY_RETRY_NOT_ALLOWED);
        }
        userRetryCount++;
        automaticRetryCount = 0;
        failureCode = null;
        enqueue(now);
    }

    private boolean owns(
            UUID token,
            Instant now
    ) {
        return status == MeetingSummaryStatus.RUNNING && claimToken.equals(token) && now.isBefore(leaseUntil);
    }

    private boolean isExpired(Instant now) {
        return status == MeetingSummaryStatus.RUNNING && !now.isBefore(leaseUntil);
    }

    private void recoverExpiredLease(Instant now) {
        if (automaticRetryCount >= MAX_AUTOMATIC_RETRIES) {
            finish(
                    MeetingSummaryStatus.FAILED,
                    "LEASE_EXPIRED",
                    now
            );
            return;
        }
        automaticRetryCount++;
        enqueue(now);
    }

    private void enqueue(Instant now) {
        status = MeetingSummaryStatus.QUEUED;
        nextRunAt = now;
        claimToken = null;
        leaseUntil = null;
        completedAt = null;
    }

    private void finish(
            MeetingSummaryStatus result,
            String code,
            Instant now
    ) {
        status = result;
        failureCode = code;
        claimToken = null;
        leaseUntil = null;
        completedAt = now;
    }
}
