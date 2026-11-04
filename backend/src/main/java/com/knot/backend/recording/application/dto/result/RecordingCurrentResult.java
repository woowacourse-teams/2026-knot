package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingConnectionPolicy;
import com.knot.backend.recording.domain.model.RecordingConnectionStatus;
import com.knot.backend.recording.domain.model.RecordingSession;
import java.time.Instant;
import java.util.List;

public record RecordingCurrentResult(
        long recordingId,
        CurrentRecordingStatus status,
        Instant startedAt,
        long elapsedMillis,
        boolean retryable,
        List<Long> retryableJobIds,
        RecordingConnectionStatus connectionStatus
) {

    public RecordingCurrentResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public RecordingCurrentResult(
            long recordingId,
            CurrentRecordingStatus status,
            Instant startedAt,
            long elapsedMillis,
            boolean retryable,
            List<Long> retryableJobIds
    ) {
        this(
                recordingId,
                status,
                startedAt,
                elapsedMillis,
                retryable,
                retryableJobIds,
                null
        );
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

    public static RecordingCurrentResult active(
            RecordingSession session,
            Instant now,
            RecordingConnectionPolicy connectionPolicy
    ) {
        return new RecordingCurrentResult(
                session.getId(),
                CurrentRecordingStatus.valueOf(
                        session.getStatus()
                                .name()
                ),
                session.getStartedAt(),
                session.getRecordingDurationMillis(now),
                false,
                List.of(),
                session.connectionStatusAt(
                        now,
                        connectionPolicy
                )
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
