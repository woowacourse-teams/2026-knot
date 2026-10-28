package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingConnectionPolicy;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;

public record RecordingHeartbeatResult(
        long recordingId,
        RecordingStatus status,
        long elapsedMillis,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant endedAt,
        RecordingEndReason endReason,
        Instant serverNow
) {

    public static RecordingHeartbeatResult of(
            RecordingSession session,
            Instant serverNow,
            RecordingConnectionPolicy connectionPolicy
    ) {
        return new RecordingHeartbeatResult(
                session.getId(),
                session.getStatus(),
                session.getRecordingDurationMillis(serverNow),
                session.getLastSeenAt(),
                session.connectionExpiresAt(connectionPolicy),
                session.getEndedAt(),
                session.getEndReason(),
                serverNow
        );
    }
}
