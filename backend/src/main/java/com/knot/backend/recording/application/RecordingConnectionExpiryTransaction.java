package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
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
    private final RecordingAudioSealing audioSealing;
    private final com.knot.backend.workspace.domain.repository.WorkspaceRepository workspaces;

    @Transactional
    public boolean expire(long recordingId) {
        Optional<Long> workspaceId = recordingSessionRepository.findWorkspaceIdById(recordingId);
        if (workspaceId.isEmpty() || workspaces.findByIdForUpdate(workspaceId.orElseThrow())
                .isEmpty()) {
            return false;
        }
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
        audioSealing.sealEnded(session);
        recordingSessionRepository.save(session);
        return true;
    }
}
