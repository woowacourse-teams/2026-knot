package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecordingConnectionExpiryTransaction {
    private final RecordingSessionRepository recordingSessionRepository;
    private final Clock clock;

    @Transactional
    public boolean expire(long recordingId) {
        Optional<RecordingSession> found = recordingSessionRepository.findByIdForUpdate(recordingId);
        if (found.isEmpty()) {
            return false;
        }
        RecordingSession session = found.orElseThrow();
        if (!session.expireIfDue(
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        )) {
            return false;
        }
        recordingSessionRepository.save(session);
        RecordingLog.committed(
                "recording.auto_end",
                "changed",
                session.getWorkspaceId(),
                session.getId(),
                null,
                null,
                session.getEndReason()
                        .name()
        );
        return true;
    }
}
