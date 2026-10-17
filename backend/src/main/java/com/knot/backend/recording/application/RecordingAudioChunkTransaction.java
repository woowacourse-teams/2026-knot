package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.AudioFrameRange;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkConfirmation;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservation;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioSaveResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecordingAudioChunkTransaction {
    private final RecordingAccess recordingAccess;
    private final RecordingSessionRepository sessions;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioChunkRepository chunks;
    private final Clock clock;

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingAudioChunkReservation reserve(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingAudioChunkCommand command,
            Instant expiresAt
    ) {
        ControlledAudio controlled = loadControlled(
                workspaceId,
                memberId,
                recordingId,
                command.control()
        );
        Optional<RecordingAudioChunk> existing = chunks.findByRecordingIdAndSequence(
                recordingId,
                command.descriptor()
                        .sequence()
        );
        if (existing.isPresent()) {
            return reserveExisting(
                    controlled,
                    existing.orElseThrow(),
                    command,
                    expiresAt
            );
        }
        validateWritable(controlled);
        controlled.stream()
                .reserve(command.descriptor());
        RecordingAudioChunk chunk = RecordingAudioChunk.reserve(
                recordingId,
                command.descriptor(),
                "recordings/" + recordingId + "/chunks/" + command.descriptor()
                        .sequence(),
                expiresAt
        );
        chunks.save(chunk);
        streams.save(controlled.stream());
        return reservation(
                chunk,
                true,
                null
        );
    }

    private RecordingAudioChunkReservation reserveExisting(
            ControlledAudio controlled,
            RecordingAudioChunk chunk,
            RecordingAudioChunkCommand command,
            Instant expiresAt
    ) {
        if (isVerified(chunk)) {
            validateDescriptor(
                    controlled,
                    chunk,
                    command
            );
            return reservation(
                    chunk,
                    false,
                    completedReservation(
                            chunk,
                            false
                    )
            );
        }
        validateWritable(controlled);
        validateDescriptor(
                controlled,
                chunk,
                command
        );
        chunk.extendUploadUrlExpiry(expiresAt);
        chunks.save(chunk);
        return reservation(
                chunk,
                false,
                null
        );
    }

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingAudioChunkReservationResult confirmReservation(
            long workspaceId,
            long memberId,
            long recordingId,
            RecordingAudioChunkCommand command,
            RecordingAudioChunkReservation reservation,
            PresignedAudioChunk signed
    ) {
        ControlledAudio controlled = loadControlled(
                workspaceId,
                memberId,
                recordingId,
                command.control()
        );
        RecordingAudioChunk chunk = findChunk(
                controlled,
                recordingId,
                reservation.sequence()
        );
        validateDescriptor(
                controlled,
                chunk,
                command
        );
        if (isVerified(chunk)) {
            return completedReservation(
                    chunk,
                    reservation.created()
            );
        }
        validateWritable(controlled);
        if (signed.expiresAt() == null || signed.expiresAt()
                .isAfter(chunk.getUploadUrlExpiresAt())
                || !signed.expiresAt()
                        .isAfter(now())) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
        return new RecordingAudioChunkReservationResult(
                reservation.created(),
                chunk.getSequence(),
                chunk.getStatus(),
                signed.uploadUrl(),
                signed.expiresAt(),
                signed.requiredHeaders()
        );
    }

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingAudioChunkConfirmation prepareConfirmation(
            long workspaceId,
            long memberId,
            long recordingId,
            int sequence,
            RecordingControlCommand command
    ) {
        ControlledAudio controlled = loadControlled(
                workspaceId,
                memberId,
                recordingId,
                command
        );
        RecordingAudioChunk chunk = findChunk(
                controlled,
                recordingId,
                sequence
        );
        if (isVerified(chunk)) {
            return new RecordingAudioChunkConfirmation(
                    null,
                    completion(chunk)
            );
        }
        validateWritable(controlled);
        return new RecordingAudioChunkConfirmation(
                chunk.getStorageKey(),
                null
        );
    }

    @Transactional(noRollbackFor = RecordingAudioCaptureExpiredException.class)
    public RecordingAudioChunkCompletionResult confirmStored(
            long workspaceId,
            long memberId,
            long recordingId,
            int sequence,
            RecordingControlCommand command,
            StoredAudioChunk stored
    ) {
        ControlledAudio controlled = loadControlled(
                workspaceId,
                memberId,
                recordingId,
                command
        );
        RecordingAudioChunk chunk = findChunk(
                controlled,
                recordingId,
                sequence
        );
        if (isVerified(chunk)) {
            return completion(chunk);
        }
        validateWritable(controlled);
        chunk.verify(
                stored,
                now()
        );
        chunks.save(chunk);
        return completion(chunk);
    }

    @Transactional(readOnly = true)
    public RecordingAudioSaveResult findState(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        RecordingSession session = sessions.findById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        session.validateControlledBy(
                workspaceId,
                memberId
        );
        validateMode(session);
        streams.findByRecordingId(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        return state(
                session,
                chunks.findAllByRecordingIdOrderBySequence(recordingId)
        );
    }

    private ControlledAudio loadControlled(
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
        validateMode(session);
        if (session.getStatus() == RecordingStatus.DISCARDED) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_DISCARDED);
        }
        RecordingAudioStream stream = streams.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        boolean expired = session.expireIfDue(now());
        if (expired) {
            sessions.save(session);
        }
        return new ControlledAudio(
                session,
                stream,
                expired
        );
    }

    private void validateWritable(ControlledAudio controlled) {
        if (controlled.expired()) {
            throw new RecordingAudioCaptureExpiredException();
        }
        if (!controlled.session()
                .isActive()) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        }
        controlled.stream()
                .validateNotFrozen();
    }

    private void validateMode(RecordingSession session) {
        if (session.getAudioMode() != RecordingAudioMode.PCM16_CHUNKS_V1) {
            throw new RecordingException(RecordingErrorCode.AUDIO_MODE_CONFLICT);
        }
    }

    private RecordingAudioChunk findChunk(
            ControlledAudio controlled,
            long recordingId,
            int sequence
    ) {
        Optional<RecordingAudioChunk> found = chunks.findByRecordingIdAndSequence(
                recordingId,
                sequence
        );
        if (controlled.expired() && (found.isEmpty() || !isVerified(found.orElseThrow()))) {
            throw new RecordingAudioCaptureExpiredException();
        }
        return found.orElseThrow(() -> new RecordingException(RecordingErrorCode.AUDIO_CHUNK_NOT_FOUND));
    }

    private void validateDescriptor(
            ControlledAudio controlled,
            RecordingAudioChunk chunk,
            RecordingAudioChunkCommand command
    ) {
        try {
            chunk.validateDescriptor(command.descriptor());
        } catch (RecordingException exception) {
            if (controlled.expired()) {
                throw new RecordingAudioCaptureExpiredException();
            }
            throw exception;
        }
    }

    private RecordingAudioChunkReservation reservation(
            RecordingAudioChunk chunk,
            boolean created,
            RecordingAudioChunkReservationResult completed
    ) {
        return new RecordingAudioChunkReservation(
                created,
                chunk.getSequence(),
                chunk.getStorageKey(),
                chunk.getByteLength(),
                chunk.getChecksumSha256(),
                chunk.getUploadUrlExpiresAt(),
                completed
        );
    }

    private RecordingAudioChunkReservationResult completedReservation(
            RecordingAudioChunk chunk,
            boolean created
    ) {
        return new RecordingAudioChunkReservationResult(
                created,
                chunk.getSequence(),
                chunk.getStatus(),
                null,
                null,
                Map.of()
        );
    }

    private RecordingAudioChunkCompletionResult completion(RecordingAudioChunk chunk) {
        List<RecordingAudioChunk> all = chunks.findAllByRecordingIdOrderBySequence(chunk.getRecordingId());
        Progress progress = progress(all);
        return new RecordingAudioChunkCompletionResult(
                chunk.getSequence(),
                chunk.getStatus(),
                progress.sequence(),
                progress.frames(),
                progress.frames() / 16,
                chunk.getVerifiedAt()
        );
    }

    private RecordingAudioSaveResult state(
            RecordingSession session,
            List<RecordingAudioChunk> all
    ) {
        Progress progress = progress(all);
        String status = audioSaveStatus(session);
        return new RecordingAudioSaveResult(
                status,
                progress.sequence(),
                progress.frames(),
                progress.frames() / 16,
                ranges(
                        all,
                        RecordingAudioChunkStatus.VERIFIED
                ),
                ranges(
                        all,
                        RecordingAudioChunkStatus.RESERVED
                ),
                session.getStatus() == RecordingStatus.ENDED,
                null,
                null,
                null,
                false,
                null,
                null
        );
    }

    private String audioSaveStatus(RecordingSession session) {
        if (session.getStatus() == RecordingStatus.DISCARDED) {
            return "DISCARDED";
        }
        if (session.isActive()) {
            return "CAPTURING";
        }
        return "DRAINING";
    }

    private Progress progress(List<RecordingAudioChunk> all) {
        int sequence = 0;
        long frames = 0;
        for (RecordingAudioChunk chunk : all) {
            if (chunk.getSequence() != sequence + 1 || chunk.getSampleOffset() != frames || !isVerified(chunk)) {
                break;
            }
            sequence = chunk.getSequence();
            frames += chunk.getFrameCount();
        }
        return new Progress(
                sequence,
                frames
        );
    }

    private List<AudioFrameRange> ranges(
            List<RecordingAudioChunk> all,
            RecordingAudioChunkStatus status
    ) {
        List<AudioFrameRange> ranges = new ArrayList<>();
        for (RecordingAudioChunk chunk : all) {
            if (chunk.getStatus() != status) {
                continue;
            }
            long start = chunk.getSampleOffset();
            long end = start + chunk.getFrameCount();
            if (!ranges.isEmpty() && ranges.getLast()
                    .endExclusive() == start) {
                AudioFrameRange previous = ranges.removeLast();
                ranges.add(
                        new AudioFrameRange(
                                previous.start(),
                                end
                        )
                );
                continue;
            }
            ranges.add(
                    new AudioFrameRange(
                            start,
                            end
                    )
            );
        }
        return List.copyOf(ranges);
    }

    private boolean isVerified(RecordingAudioChunk chunk) {
        return chunk.getStatus() == RecordingAudioChunkStatus.VERIFIED;
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }

    private record ControlledAudio(
            RecordingSession session,
            RecordingAudioStream stream,
            boolean expired
    ) {
    }

    private record Progress(
            int sequence,
            long frames
    ) {
    }
}
