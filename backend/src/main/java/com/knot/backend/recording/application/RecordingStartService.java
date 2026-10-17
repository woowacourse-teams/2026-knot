package com.knot.backend.recording.application;

import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingStartService {
    private final RecordingAccess recordingAccess;
    private final MemberRepository memberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingControlTokenHasher controlTokenHasher;
    private final Clock clock;

    @Transactional
    public RecordingStartResult start(
            long workspaceId,
            long memberId,
            RecordingStartCommand command
    ) {
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.start",
                workspaceId,
                null,
                null,
                null
        );
        return trace.call(() -> {
            recordingAccess.lockMember(
                    workspaceId,
                    memberId
            );
            memberRepository.findByIdForUpdate(memberId)
                    .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_MEMBER_NOT_FOUND));

            String controlTokenHash = controlTokenHasher.hash(command.controlToken());
            Optional<RecordingSession> existing = recordingSessionRepository.findByMemberIdAndRequestIdForUpdate(
                    memberId,
                    command.requestId()
            );
            if (existing.isPresent()) {
                RecordingSession session = existing.orElseThrow();
                trace.recording(session.getId())
                        .outcome("replayed");
                return replay(
                        session,
                        workspaceId,
                        command,
                        controlTokenHash
                );
            }
            releaseActiveRecordings(memberId);

            RecordingSession session = RecordingSession.start(
                    workspaceId,
                    memberId,
                    command.requestId(),
                    command.tabId(),
                    controlTokenHash,
                    now()
            );
            RecordingSession saved = recordingSessionRepository.save(session);
            trace.recording(saved.getId())
                    .outcome("created");
            return RecordingStartResult.from(
                    saved,
                    true
            );
        });
    }

    private RecordingStartResult replay(
            RecordingSession session,
            long workspaceId,
            RecordingStartCommand command,
            String controlTokenHash
    ) {
        session.validateStartRetry(
                workspaceId,
                command.tabId(),
                controlTokenHash
        );
        if (session.expireIfDue(now())) {
            recordingSessionRepository.save(session);
            logAutoEnded(session);
        }
        return RecordingStartResult.from(
                session,
                false
        );
    }

    private void releaseActiveRecordings(long memberId) {
        Instant now = now();
        for (RecordingSession active : recordingSessionRepository.findAllActiveByMemberIdForUpdate(memberId)) {
            active.releaseForNewRecording(now);
            recordingSessionRepository.save(active);
            logAutoEnded(active);
        }
    }

    private void logAutoEnded(RecordingSession session) {
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
