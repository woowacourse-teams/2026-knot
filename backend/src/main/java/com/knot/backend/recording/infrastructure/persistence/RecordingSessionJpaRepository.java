package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RecordingSessionJpaRepository extends JpaRepository<RecordingSession, Long> {

    @Query("select s.workspaceId from RecordingSession s where s.id = :recordingId")
    Optional<Long> findWorkspaceIdById(long recordingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingSession> findWithLockById(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingSession> findWithLockByMemberIdAndRequestId(
            long memberId,
            UUID requestId
    );

    @Query(value = """
            SELECT id FROM recording_sessions
            WHERE status IN ('RECORDING', 'PAUSED')
              AND (
                  last_seen_at <= :disconnectionThreshold
                  OR (
                      status = 'RECORDING'
                      AND current_interval_started_at
                          + (:maxRecordingMillis - accumulated_recording_millis) * INTERVAL '1 millisecond' <= :now
                  )
              )
            ORDER BY last_seen_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findActiveIdsDueAt(
            Instant disconnectionThreshold,
            Instant now,
            long maxRecordingMillis,
            int limit
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RecordingSession> findAllByMemberIdAndStatusInOrderByIdAsc(
            long memberId,
            Collection<RecordingStatus> statuses
    );

    Optional<RecordingSession> findByWorkspaceIdAndMemberIdAndStatusIn(
            long workspaceId,
            long memberId,
            Collection<RecordingStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RecordingSession> findAllByWorkspaceIdAndStatusInOrderByIdAsc(
            long workspaceId,
            Collection<RecordingStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RecordingSession> findAllByWorkspaceIdAndMemberIdAndStatusInOrderByIdAsc(
            long workspaceId,
            long memberId,
            Collection<RecordingStatus> statuses
    );
}
