package com.knot.backend.recording.application;

import com.knot.backend.global.config.RecordingAudioChunksProperties;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
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
public class RecordingStartService {
    private final RecordingAccess recordingAccess;
    private final MemberRepository memberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingControlTokenHasher recordingControlTokenHasher;
    private final Clock clock;
    private final RecordingAudioSealing audioSealing;
    private final RecordingAudioStreamRepository recordingAudioStreamRepository;
    private final RecordingAudioChunksProperties audioChunksProperties;

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
        if (command.audioMode() == RecordingAudioMode.PCM16_CHUNKS_V1 && !audioChunksProperties.enabled()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_MODE_DISABLED);
        }
        releaseActiveRecordings(memberId);

        RecordingSession session = RecordingSession.start(
                workspaceId,
                memberId,
                command.requestId(),
                command.tabId(),
                controlTokenHash,
                now(),
                command.audioMode()
        );
        RecordingSession saved = recordingSessionRepository.save(session);
        if (saved.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1) {
            recordingAudioStreamRepository.save(RecordingAudioStream.create(saved.getId()));
        }
        return RecordingStartResult.from(
                saved,
                true
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
                controlTokenHash,
                command.audioMode()
        );
        if (session.expireIfDue(now())) {
            audioSealing.sealEnded(session);
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
            audioSealing.sealEnded(active);
            recordingSessionRepository.save(active);
        }
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
