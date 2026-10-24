package com.knot.backend.recording.application;

import com.knot.backend.document.application.DocumentGenerationProgressService;
import com.knot.backend.document.application.dto.result.DocumentGenerationHomeResult;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import java.util.List;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.model.RecordingAudioSealKind;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand;
import com.knot.backend.recording.application.dto.result.RecordingAudioSealSummary;
import com.knot.backend.global.config.RecordingAudioChunksProperties;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
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
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class RecordingService {
    private final RecordingAccess recordingAccess;
    private final MemberRepository memberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final RecordingControlTokenHasher recordingControlTokenHasher;
    private final Clock clock;
    private final RecordingAudioSealing audioSealing;
    private final RecordingAudioStreamRepository recordingAudioStreamRepository;
    private final RecordingAudioChunksProperties audioChunksProperties;
    private final RecordingAudioChunkRepository chunks;
    private final RecordingAudioFinalizationRepository finalizations;
    private final RecordingAudioCleanupRepository audioCleanup;
    private final RecordingTranscriptionRepository transcriptions;
    private final DocumentGenerationProgressService documentProgress;
    private final RecordingAudioUploadRepository uploads;
    private final com.knot.backend.global.config.TranscriptionProperties transcriptionProperties;

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

            String controlTokenHash = recordingControlTokenHasher.hash(command.controlToken());
            Optional<RecordingSession> existing = recordingSessionRepository.findByMemberIdAndRequestIdForUpdate(
                    memberId,
                    command.requestId()
            );
            if (existing.isPresent()) {
                trace.recording(
                        existing.orElseThrow()
                                .getId()
                )
                        .outcome("replayed");
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
            if (session.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1
                    && audioChunksProperties.incrementalEnabled()) {
                session.enableIncrementalTranscription("pcm-window-v1-30-5");
            }
            RecordingSession saved = recordingSessionRepository.save(session);
            if (saved.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1) {
                recordingAudioStreamRepository.save(RecordingAudioStream.create(saved.getId()));
                audioCleanup.track(
                        saved.getId(),
                        now()
                );
            }
            trace.recording(saved.getId())
                    .outcome("created");
            return RecordingStartResult.from(
                    saved,
                    true
            );
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RecordingCurrentResult> findCurrent(
            long workspaceId,
            long memberId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        Instant decidedAt = now();
        Optional<RecordingSession> candidate = recordingSessionRepository.findCurrentByWorkspaceIdAndMemberId(
                workspaceId,
                memberId,
                decidedAt
        );
        Instant measuredAt = now();
        return candidate.flatMap(
                session -> currentResult(
                        session,
                        decidedAt,
                        measuredAt
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
            Instant now = now();
            try {
                expireAndSeal(
                        session,
                        now
                );
                session.pause(now);
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
            Instant now = now();
            try {
                expireAndSeal(
                        session,
                        now
                );
                session.resume(now);
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
        RecordingLog.Scope trace = RecordingLog.begin(
                "recording.end",
                workspaceId,
                recordingId,
                null,
                null
        );
        return trace.call(() -> {
            RecordingSession session = loadForEnd(
                    workspaceId,
                    memberId,
                    recordingId,
                    command
            );
            RecordingStatus previous = session.getStatus();
            try {
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
                trace.outcome(previous == session.getStatus() ? "replayed" : "changed");
                trace.endReason(
                        session.getEndReason()
                                .name()
                );
                if (previous != session.getStatus() && session.getEndReason() != RecordingEndReason.USER_ENDED) {
                    trace.outcome("ignored");
                }
                if (session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1) {
                    return RecordingEndResult.from(session);
                }
                RecordingAudioStream stream = recordingAudioStreamRepository.findByRecordingIdForUpdate(recordingId)
                        .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
                return new RecordingEndResult(
                        session.getId(),
                        session.getStatus(),
                        session.getEndedAt(),
                        new RecordingAudioSealSummary(
                                finalizations.findByRecordingId(recordingId)
                                        .map(job -> job.audioSaveStatus())
                                        .orElse("DRAINING"),
                                stream.getLastSequence(),
                                stream.getTotalFrameCount(),
                                stream.isTailUnknown(),
                                stream.getAcceptUntil()
                        )
                );
            } finally {
                logAutoEnded(
                        session,
                        previous
                );
            }
        });
    }

    private Optional<RecordingCurrentResult> currentResult(
            RecordingSession session,
            Instant decidedAt,
            Instant measuredAt
    ) {
        if (session.isActive()) {
            return Optional.of(
                    RecordingCurrentResult.of(
                            session,
                            CurrentRecordingStatus.valueOf(
                                    session.getStatus()
                                            .name()
                            ),
                            measuredAt
                    )
            );
        }
        if (session.getStatus() != RecordingStatus.ENDED) {
            return Optional.empty();
        }
        Optional<DocumentGenerationHomeResult> progress = documentProgress.findHomeProgress(
                session.getWorkspaceId(),
                session.getId(),
                decidedAt
        );
        if (progress.isPresent()) {
            return documentCurrentResult(
                    session,
                    progress.orElseThrow(),
                    measuredAt
            );
        }
        boolean transcriptionFailed = transcriptions.findByRecordingSessionId(session.getId())
                .map(RecordingTranscription::getStatus)
                .filter(TranscriptionStatus.FAILED::equals)
                .isPresent();
        if (transcriptionFailed) {
            boolean retryable = transcriptionProperties.enabled() && uploads.findByRecordingId(session.getId())
                    .filter(RecordingAudioUpload::isAudioAvailable)
                    .isPresent();
            return Optional.of(
                    failedResult(
                            session,
                            CurrentRecordingStatus.TRANSCRIPTION_FAILED,
                            retryable,
                            List.of(),
                            measuredAt
                    )
            );
        }
        boolean audioProcessing = finalizations.findByRecordingId(session.getId())
                .map(
                        finalization -> finalization.getStatus() != AudioFinalizationStatus.FAILED
                                && finalization.getStatus() != AudioFinalizationStatus.DISCARDED
                )
                .orElse(true);
        if (audioProcessing) {
            return Optional.of(
                    RecordingCurrentResult.of(
                            session,
                            CurrentRecordingStatus.PROCESSING,
                            measuredAt
                    )
            );
        }
        return Optional.empty();
    }

    private Optional<RecordingCurrentResult> documentCurrentResult(
            RecordingSession session,
            DocumentGenerationHomeResult progress,
            Instant measuredAt
    ) {
        if (progress.status() == DocumentGenerationProcessingStatus.QUEUED
                || progress.status() == DocumentGenerationProcessingStatus.RUNNING) {
            return Optional.of(
                    RecordingCurrentResult.of(
                            session,
                            CurrentRecordingStatus.PROCESSING,
                            measuredAt
                    )
            );
        }
        if (progress.status() == DocumentGenerationProcessingStatus.FAILED && progress.hasVisibleFailure()) {
            return Optional.of(
                    failedResult(
                            session,
                            CurrentRecordingStatus.DOCUMENT_FAILED,
                            !progress.retryableJobIds()
                                    .isEmpty(),
                            progress.retryableJobIds(),
                            measuredAt
                    )
            );
        }
        return Optional.empty();
    }

    private RecordingCurrentResult failedResult(
            RecordingSession session,
            CurrentRecordingStatus status,
            boolean retryable,
            List<Long> retryableJobIds,
            Instant measuredAt
    ) {
        return new RecordingCurrentResult(
                session.getId(),
                status,
                session.getStartedAt(),
                session.getRecordingDurationMillis(measuredAt),
                retryable,
                retryableJobIds
        );
    }

    private void normalSeal(
            RecordingSession session,
            RecordingAudioSealCommand seal,
            Instant endedAt
    ) {
        session.validateNotDiscarded();
        RecordingAudioStream stream = recordingAudioStreamRepository.findByRecordingIdForUpdate(session.getId())
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
        recordingAudioStreamRepository.save(stream);
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
        RecordingStatus previous = session.getStatus();
        session.recordHeartbeat(now);
        audioSealing.sealEnded(session);
        logAutoEnded(
                session,
                previous
        );
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
                controlTokenHash,
                command.audioMode()
        );
        RecordingStatus previous = session.getStatus();
        if (session.expireIfDue(now())) {
            audioSealing.sealEnded(session);
            recordingSessionRepository.save(session);
            logAutoEnded(
                    session,
                    previous
            );
        }
        return RecordingStartResult.from(
                session,
                false
        );
    }

    private void releaseActiveRecordings(long memberId) {
        Instant now = now();
        for (RecordingSession active : recordingSessionRepository.findAllActiveByMemberIdForUpdate(memberId)) {
            RecordingStatus previous = active.getStatus();
            active.releaseForNewRecording(now);
            audioSealing.sealEnded(active);
            recordingSessionRepository.save(active);
            logAutoEnded(
                    active,
                    previous
            );
        }
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
