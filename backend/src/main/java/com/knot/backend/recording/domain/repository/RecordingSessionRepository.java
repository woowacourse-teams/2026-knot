package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingSessionRepository {

    RecordingSession save(RecordingSession recordingSession);

    Optional<RecordingSession> findById(Long recordingSessionId);

    Optional<Long> findWorkspaceIdById(long recordingId);

    Optional<RecordingSession> findByIdForUpdate(long recordingSessionId);

    Optional<RecordingSession> findByMemberIdAndRequestIdForUpdate(
            long memberId,
            UUID requestId
    );

    List<RecordingSession> findAllActiveByMemberIdForUpdate(long memberId);

    List<Long> findActiveIdsDueAt(
            Instant now,
            Instant expiredLastSeenThreshold,
            int limit
    );

    Optional<RecordingSession> findActiveByWorkspaceIdAndMemberId(
            long workspaceId,
            long memberId
    );

    Optional<RecordingSession> findLatestByWorkspaceIdAndMemberId(
            long workspaceId,
            long memberId
    );

    Optional<RecordingSession> findCurrentByWorkspaceIdAndMemberId(
            long workspaceId,
            long memberId,
            Instant now
    );

    List<RecordingSession> findAllActiveByWorkspaceIdForUpdate(long workspaceId);

    List<RecordingSession> findAllActiveByWorkspaceIdAndMemberIdForUpdate(
            long workspaceId,
            long memberId
    );
}
