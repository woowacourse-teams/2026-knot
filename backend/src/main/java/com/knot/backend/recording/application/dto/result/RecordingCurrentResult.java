package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingSession;
import java.time.Instant;
import java.util.List;

public record RecordingCurrentResult(
        long recordingId,
        CurrentRecordingStatus status,
        Instant startedAt,
        long elapsedMillis,
        boolean retryable,
        List<Long> retryableJobIds
) {

    public RecordingCurrentResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public RecordingCurrentResult(
            long recordingId,
            CurrentRecordingStatus status,
            Instant startedAt,
            long elapsedMillis
    ) {
        this(
                recordingId,
                status,
                startedAt,
                elapsedMillis,
                false,
                List.of()
        );
    }

    public static RecordingCurrentResult of(
            RecordingSession session,
            CurrentRecordingStatus status,
            Instant now
    ) {
        return new RecordingCurrentResult(
                session.getId(),
                status,
                session.getStartedAt(),
                session.getRecordingDurationMillis(now)
        );
    }
}
