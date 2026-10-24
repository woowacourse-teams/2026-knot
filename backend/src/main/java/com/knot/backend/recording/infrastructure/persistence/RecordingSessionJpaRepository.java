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

    Optional<RecordingSession> findFirstByWorkspaceIdAndMemberIdOrderByStartedAtDescIdDesc(
            long workspaceId,
            long memberId
    );

    @Query(value = """
            WITH candidates AS (
                SELECT s.id, s.started_at,
                    CASE
                        WHEN s.status IN ('RECORDING', 'PAUSED') THEN 0
                        WHEN b.processing_status IN ('QUEUED', 'RUNNING') THEN 1
                        WHEN b.id IS NULL AND COALESCE(t.status, '') <> 'FAILED'
                            AND (f.id IS NULL OR f.status NOT IN ('FAILED', 'DISCARDED')) THEN 1
                        WHEN b.id IS NULL AND t.status = 'FAILED' THEN 2
                        WHEN b.processing_status = 'FAILED' AND EXISTS (
                            SELECT 1 FROM document_generation_jobs j
                            WHERE j.batch_id = b.id AND j.status = 'FAILED' AND j.expires_at > :now
                        ) THEN 2
                        ELSE 3
                    END AS priority
                FROM recording_sessions s
                LEFT JOIN recording_transcriptions t ON t.recording_session_id = s.id
                LEFT JOIN document_generation_batches b ON b.recording_session_id = s.id
                LEFT JOIN recording_audio_finalizations f ON f.recording_id = s.id
                WHERE s.workspace_id = :workspaceId AND s.member_id = :memberId
                    AND s.status IN ('RECORDING', 'PAUSED', 'ENDED')
            )
            SELECT s.* FROM candidates c
            JOIN recording_sessions s ON s.id = c.id
            WHERE c.priority < 3
            ORDER BY c.priority, c.started_at DESC, c.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<RecordingSession> findCurrentByWorkspaceIdAndMemberId(
            long workspaceId,
            long memberId,
            Instant now
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
