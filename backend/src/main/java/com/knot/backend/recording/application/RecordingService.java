package com.knot.backend.recording.application;

import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.RecordingResumeResult;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingService {
    private final RecordingAccess recordingAccess;
    private final MemberRepository memberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingControlTokenHasher recordingControlTokenHasher;
    private final Clock clock;

    @Transactional
    public RecordingStartResult start(
            long workspaceId,
            long memberId,
            RecordingStartCommand command
    ) {
        recordingAccess.lockMember(
                workspaceId,
                memberId
        );
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_MEMBER_NOT_FOUND));

        String controlTokenHash = recordingControlTokenHasher.hash(command.controlToken());
        Optional<RecordingSession> existing = recordingSessionRepository.findByMemberIdAndRequestIdForUpdate(
                memberId,
                command.requestId()
        );
        if (existing.isPresent()) {
            return replay(
                    existing.orElseThrow(),
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
        return RecordingStartResult.from(
                recordingSessionRepository.save(session),
                true
        );
    }

    @Transactional(readOnly = true)
    public Optional<RecordingCurrentResult> findCurrent(
            long workspaceId,
            long memberId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        Optional<RecordingSession> activeSession = recordingSessionRepository.findActiveByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        );
        Instant now = clock.instant();
        return activeSession.map(
                session -> RecordingCurrentResult.of(
                        session,
                        now
                )
        );
    }

    @Transactional(noRollbackFor = RecordingException.class)
    public RecordingPauseResult pause(
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
        session.pause(now());
        return RecordingPauseResult.from(recordingSessionRepository.save(session));
    }

    @Transactional(noRollbackFor = RecordingException.class)
    public RecordingResumeResult resume(
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
        session.resume(now());
        return RecordingResumeResult.from(recordingSessionRepository.save(session));
    }

    @Transactional
    public RecordingEndResult end(
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
        session.end(now());
        return RecordingEndResult.from(recordingSessionRepository.save(session));
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
        session.recordHeartbeat(now);
        return RecordingHeartbeatResult.of(
                recordingSessionRepository.save(session),
                now
        );
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
        }
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
