package com.knot.backend.recording.application;

import java.util.Optional;

import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;

import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;

import com.knot.backend.recording.domain.model.RecordingAudioStream;

import com.knot.backend.recording.domain.model.RecordingAudioSealKind;

import com.knot.backend.recording.domain.model.RecordingAudioMode;

import com.knot.backend.recording.domain.model.RecordingAudioChunk;

import com.knot.backend.recording.domain.model.AudioChunkDescriptor;

import com.knot.backend.recording.domain.RecordingErrorCode;

import com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioSealSummary;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.RecordingResumeResult;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
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
    private final RecordingAudioSealing audioSealing;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioChunkRepository chunks;

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
        Instant now = now();
        expireAndSeal(
                session,
                now
        );
        session.pause(now);
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
        Instant now = now();
        expireAndSeal(
                session,
                now
        );
        session.resume(now);
        return RecordingResumeResult.from(recordingSessionRepository.save(session));
    }

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingEndResult end(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        return end(
                workspaceId,
                memberId,
                recordingId,
                command,
                null
        );
    }

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingEndResult end(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command,
            RecordingAudioSealCommand seal
    ) {
        RecordingSession session = loadForEnd(
                workspaceId,
                memberId,
                recordingId,
                command
        );
        if (seal != null && session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1) {
            throw new RecordingException(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        }
        Instant endedAt = now();
        boolean expired = expireAndSeal(
                session,
                endedAt
        );
        sealCapture(
                session,
                command,
                seal,
                endedAt,
                expired
        );
        recordingSessionRepository.save(session);
        if (session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1) {
            return RecordingEndResult.from(session);
        }
        RecordingAudioStream stream = streams.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        return new RecordingEndResult(
                session.getId(),
                session.getStatus(),
                session.getEndedAt(),
                new RecordingAudioSealSummary(
                        "DRAINING",
                        stream.getLastSequence(),
                        stream.getTotalFrameCount(),
                        stream.isTailUnknown(),
                        stream.getAcceptUntil()
                )
        );
    }

    private void normalSeal(
            RecordingSession session,
            RecordingAudioSealCommand seal,
            Instant endedAt
    ) {
        session.validateNotDiscarded();
        RecordingAudioStream stream = streams.findByRecordingIdForUpdate(session.getId())
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        if (stream.isSealed()) {
            stream.validateNormalSeal(
                    seal.lastSequence(),
                    seal.totalFrameCount()
            );
            for (AudioChunkDescriptor descriptor : seal.pendingDescriptors()) {
                chunks.findByRecordingIdAndSequence(
                        session.getId(),
                        descriptor.sequence()
                )
                        .orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_SEAL_CONFLICT))
                        .validateDescriptor(descriptor);
            }
            return;
        }
        for (AudioChunkDescriptor descriptor : seal.pendingDescriptors()) {
            reservePending(
                    session,
                    stream,
                    descriptor,
                    endedAt
            );
        }
        stream.validateNormalSeal(
                seal.lastSequence(),
                seal.totalFrameCount()
        );
        session.end(endedAt);
        stream.seal(
                RecordingAudioSealKind.NORMAL,
                session.getEndedAt()
        );
        streams.save(stream);
    }

    private RecordingSession loadForEnd(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingControlCommand command
    ) {
        if (command == null) {
            return recordingAccess.loadOwnedForUpdate(
                    workspaceId,
                    memberId,
                    recordingId
            );
        }
        return recordingAccess.loadControlledForUpdate(
                workspaceId,
                memberId,
                recordingId,
                command
        );
    }

    private void sealCapture(
            RecordingSession session,
            RecordingControlCommand command,
            RecordingAudioSealCommand seal,
            Instant endedAt,
            boolean expired
    ) {
        if (seal == null) {
            session.end(endedAt);
            audioSealing.sealEnded(session);
            return;
        }
        if (command == null) {
            throw new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        }
        if (expired) {
            throw new RecordingAudioCaptureExpiredException();
        }
        normalSeal(
                session,
                seal,
                endedAt
        );
    }

    private void reservePending(
            RecordingSession session,
            RecordingAudioStream stream,
            AudioChunkDescriptor descriptor,
            Instant endedAt
    ) {
        Optional<RecordingAudioChunk> existing = chunks.findByRecordingIdAndSequence(
                session.getId(),
                descriptor.sequence()
        );
        if (existing.isPresent()) {
            existing.orElseThrow()
                    .validateDescriptor(descriptor);
            return;
        }
        stream.reserve(descriptor);
        chunks.save(
                RecordingAudioChunk.reserve(
                        session.getId(),
                        descriptor,
                        "recordings/" + session.getId() + "/chunks/" + descriptor.sequence(),
                        endedAt.plusSeconds(120)
                )
        );
    }

    private boolean expireAndSeal(
            RecordingSession session,
            Instant now
    ) {
        boolean expired = session.expireIfDue(now);
        audioSealing.sealEnded(session);
        return expired;
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
        audioSealing.sealEnded(session);
        return RecordingHeartbeatResult.of(
                recordingSessionRepository.save(session),
                now
        );
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
