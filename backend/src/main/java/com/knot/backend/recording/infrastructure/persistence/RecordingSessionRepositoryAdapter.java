package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

@Repository
@RequiredArgsConstructor
public class RecordingSessionRepositoryAdapter implements RecordingSessionRepository {
    private final RecordingSessionJpaRepository recordingSessionJpaRepository;

    @Override
    public RecordingSession save(RecordingSession recordingSession) {
        try {
            return recordingSessionJpaRepository.saveAndFlush(recordingSession);
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    @Override
    public Optional<RecordingSession> findById(Long recordingSessionId) {
        return recordingSessionJpaRepository.findById(recordingSessionId);
    }

    @Override
    public Optional<Long> findWorkspaceIdById(long recordingId) {
        return recordingSessionJpaRepository.findWorkspaceIdById(recordingId);
    }

    @Override
    public Optional<RecordingSession> findByIdForUpdate(long recordingSessionId) {
        return recordingSessionJpaRepository.findWithLockById(recordingSessionId);
    }

    @Override
    public Optional<RecordingSession> findByMemberIdAndRequestIdForUpdate(
            long memberId,
            UUID requestId
    ) {
        return recordingSessionJpaRepository.findWithLockByMemberIdAndRequestId(
                memberId,
                requestId
        );
    }

    @Override
    public List<RecordingSession> findAllActiveByMemberIdForUpdate(long memberId) {
        return recordingSessionJpaRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                memberId,
                RecordingStatus.ACTIVE_STATUSES
        );
    }

    @Override
    public List<Long> findActiveIdsDueAt(
            Instant now,
            int limit
    ) {
        return recordingSessionJpaRepository.findActiveIdsDueAt(
                RecordingSession.disconnectionThreshold(now),
                now,
                RecordingSession.MAX_RECORDING_MILLIS,
                limit
        );
    }

    @Override
    public Optional<RecordingSession> findActiveByWorkspaceIdAndMemberId(
            long workspaceId,
            long memberId
    ) {
        return recordingSessionJpaRepository.findByWorkspaceIdAndMemberIdAndStatusIn(
                workspaceId,
                memberId,
                RecordingStatus.ACTIVE_STATUSES
        );
    }

    @Override
    public List<RecordingSession> findAllActiveByWorkspaceIdForUpdate(long workspaceId) {
        return recordingSessionJpaRepository.findAllByWorkspaceIdAndStatusInOrderByIdAsc(
                workspaceId,
                RecordingStatus.ACTIVE_STATUSES
        );
    }

    @Override
    public List<RecordingSession> findAllActiveByWorkspaceIdAndMemberIdForUpdate(
            long workspaceId,
            long memberId
    ) {
        return recordingSessionJpaRepository.findAllByWorkspaceIdAndMemberIdAndStatusInOrderByIdAsc(
                workspaceId,
                memberId,
                RecordingStatus.ACTIVE_STATUSES
        );
    }

    private RecordingException translateIntegrityViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (ConstraintViolationException.class.isInstance(cause)) {
                ConstraintViolationException violation = ConstraintViolationException.class.cast(cause);
                RecordingErrorCode code = switch (String.valueOf(violation.getConstraintName())) {
                    case "uk_recording_sessions_member_active" -> RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS;
                    case "uk_recording_sessions_member_request" -> RecordingErrorCode.RECORDING_START_REQUEST_CONFLICT;
                    default -> RecordingErrorCode.INVALID_RECORDING_DATA;
                };
                return new RecordingException(
                        code,
                        exception
                );
            }
            cause = cause.getCause();
        }
        return new RecordingException(
                RecordingErrorCode.INVALID_RECORDING_DATA,
                exception
        );
    }
}
