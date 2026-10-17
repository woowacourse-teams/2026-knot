package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.RecordingResumeResult;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingEndReason;
import com.knot.backend.recording.domain.RecordingStatus;
import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingControlService {
    private final RecordingAccess recordingAccess;
    private final RecordingSessionRepository recordingSessionRepository;
    private final Clock clock;

    @Transactional(noRollbackFor = RecordingException.class)
    public RecordingPauseResult pause(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.pause",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            RecordingSession session = recordingAccess.loadControlledForUpdate(
                    workspaceId,
                    memberId,
                    recordingId,
                    command
            );
            RecordingStatus previous = session.getStatus();
            try {
                session.pause(now());
            } finally {
                logAutoEnded(
                        session,
                        previous
                );
            }
            trace.outcome(previous == session.getStatus() ? "replayed" : "changed");
            return RecordingPauseResult.from(recordingSessionRepository.save(session));
        });
    }

    @Transactional(noRollbackFor = RecordingException.class)
    public RecordingResumeResult resume(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.resume",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            RecordingSession session = recordingAccess.loadControlledForUpdate(
                    workspaceId,
                    memberId,
                    recordingId,
                    command
            );
            RecordingStatus previous = session.getStatus();
            try {
                session.resume(now());
            } finally {
                logAutoEnded(
                        session,
                        previous
                );
            }
            trace.outcome(previous == session.getStatus() ? "replayed" : "changed");
            return RecordingResumeResult.from(recordingSessionRepository.save(session));
        });
    }

    @Transactional
    public RecordingEndResult end(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.end",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            RecordingSession session = recordingAccess.loadControlledForUpdate(
                    workspaceId,
                    memberId,
                    recordingId,
                    command
            );
            RecordingStatus previous = session.getStatus();
            try {
                session.end(now());
            } finally {
                logAutoEnded(
                        session,
                        previous
                );
            }
            trace.outcome(previous == session.getStatus() ? "replayed" : "changed");
            trace.endReason(
                    session.getEndReason()
                            .name()
            );
            if (previous != session.getStatus() && session.getEndReason() != RecordingEndReason.USER_ENDED) {
                trace.outcome("ignored");
            }
            return RecordingEndResult.from(recordingSessionRepository.save(session));
        });
    }

    @Transactional
    public RecordingHeartbeatResult heartbeat(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        RecordingSession session = recordingAccess.loadControlledForUpdate(
                workspaceId,
                memberId,
                recordingId,
                command
        );
        Instant now = now();
        RecordingStatus previous = session.getStatus();
        session.recordHeartbeat(now);
        logAutoEnded(
                session,
                previous
        );
        return RecordingHeartbeatResult.of(
                recordingSessionRepository.save(session),
                now
        );
    }

    private void logAutoEnded(
            RecordingSession session,
            RecordingStatus previous
    ) {
        if (previous == RecordingStatus.ENDED || session.getStatus() != RecordingStatus.ENDED
                || session.getEndReason() == RecordingEndReason.USER_ENDED) {
            return;
        }
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
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
