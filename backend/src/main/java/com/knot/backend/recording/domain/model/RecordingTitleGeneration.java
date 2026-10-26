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
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_title_generations")
public class RecordingTitleGeneration {
    public static final int MAX_ATTEMPTS = 3;
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private long recordingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordingTitleGenerationStatus status;

    @Column(length = RecordingTitle.MAX_LENGTH)
    private String title;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RecordingTitleGeneration() {}

    public static RecordingTitleGeneration queue(
            long recordingId,
            Instant now
    ) {
        if (recordingId <= 0 || now == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        RecordingTitleGeneration generation = new RecordingTitleGeneration();
        generation.recordingId = recordingId;
        generation.status = RecordingTitleGenerationStatus.QUEUED;
        generation.nextRunAt = now;
        generation.createdAt = now;
        return generation;
    }

    public boolean claim(
            Instant now,
            Duration lease
    ) {
        if (isAbandoned(now) && attemptCount >= MAX_ATTEMPTS) {
            finish(
                    RecordingTitleGenerationStatus.FAILED,
                    LEASE_EXPIRED,
                    now
            );
            return false;
        }
        if (!isDue(now) && !isAbandoned(now)) {
            return false;
        }
        status = RecordingTitleGenerationStatus.RUNNING;
        attemptCount++;
        leaseUntil = now.plus(lease);
        return true;
    }

    public boolean succeed(
            int attempt,
            String generatedTitle,
            Instant now
    ) {
        if (!isCurrentAttempt(attempt)) {
            return false;
        }
        title = RecordingTitle.normalize(generatedTitle);
        finish(
                RecordingTitleGenerationStatus.SUCCEEDED,
                null,
                now
        );
        return true;
    }

    public boolean fail(
            int attempt,
            String code,
            Instant now,
            Duration backoff
    ) {
        if (!isCurrentAttempt(attempt)) {
            return false;
        }
        if (attemptCount >= MAX_ATTEMPTS) {
            finish(
                    RecordingTitleGenerationStatus.FAILED,
                    code,
                    now
            );
            return true;
        }
        status = RecordingTitleGenerationStatus.QUEUED;
        failureCode = code;
        nextRunAt = now.plus(backoff);
        leaseUntil = null;
        return true;
    }

    private boolean isDue(Instant now) {
        return status == RecordingTitleGenerationStatus.QUEUED && !now.isBefore(nextRunAt);
    }

    private boolean isAbandoned(Instant now) {
        return status == RecordingTitleGenerationStatus.RUNNING && !now.isBefore(leaseUntil);
    }

    private boolean isCurrentAttempt(int attempt) {
        return status == RecordingTitleGenerationStatus.RUNNING && attemptCount == attempt;
    }

    private void finish(
            RecordingTitleGenerationStatus finalStatus,
            String code,
            Instant now
    ) {
        status = finalStatus;
        failureCode = code;
        leaseUntil = null;
        completedAt = now;
    }
}
