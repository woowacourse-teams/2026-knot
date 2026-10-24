package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioCleanup;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupPolicy;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupScope;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class RecordingAudioCleanupRepositoryAdapter implements RecordingAudioCleanupRepository {
    private static final String POLICY_SQL = """
            SELECT t.recording_id,
                CASE WHEN r.id IS NULL OR r.status = 'DISCARDED' OR w.id IS NULL OR w.deleted_at IS NOT NULL
                    OR NOT EXISTS (SELECT 1 FROM workspace_members m WHERE m.workspace_id = r.workspace_id
                        AND m.member_id = r.member_id AND m.left_at IS NULL)
                    OR EXISTS (SELECT 1 FROM document_generation_batches b WHERE b.recording_session_id = r.id
                        AND b.processing_status = 'NO_CONTENT')
                    OR j.failure_code = 'AUDIO_NO_CONTENT'
                THEN 'ALL' WHEN j.status = 'READY' THEN 'RAW' ELSE 'DORMANT' END AS desired_scope,
                (SELECT MAX(c.upload_url_expires_at) FROM recording_audio_chunks c
                    WHERE c.recording_id = t.recording_id)
                    AS max_url_expires_at
            FROM recording_audio_cleanup t
            LEFT JOIN recording_sessions r ON r.id = t.recording_id
            LEFT JOIN workspaces w ON w.id = r.workspace_id
            LEFT JOIN recording_audio_finalizations j ON j.recording_id = t.recording_id
            """;
    private final EntityManager entityManager;

    @Override
    public List<Long> findOwned(
            long workspaceId,
            long memberId
    ) {
        return entityManager.createNativeQuery(
                """
                        SELECT r.id FROM recording_sessions r
                        WHERE r.workspace_id = :workspaceId AND r.member_id = :memberId
                            AND (EXISTS (SELECT 1 FROM recording_audio_streams s WHERE s.recording_id=r.id)
                                OR EXISTS (SELECT 1 FROM recording_transcription_inputs i WHERE i.recording_id=r.id))
                        ORDER BY r.id
                        """,
                Long.class
        )
                .setParameter(
                        "workspaceId",
                        workspaceId
                )
                .setParameter(
                        "memberId",
                        memberId
                )
                .getResultList();
    }

    @Override
    public void track(
            long recordingId,
            Instant now
    ) {
        entityManager.createNativeQuery("""
                INSERT INTO recording_audio_cleanup (recording_id, storage_prefix, created_at)
                VALUES (:id, :prefix, :now) ON CONFLICT (recording_id) DO NOTHING
                """)
                .setParameter(
                        "id",
                        recordingId
                )
                .setParameter(
                        "prefix",
                        "recordings/" + recordingId + "/"
                )
                .setParameter(
                        "now",
                        now
                )
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findCandidates(int limit) {
        return entityManager.createNativeQuery(
                """
                        SELECT p.recording_id FROM (
                        """ + POLICY_SQL + """
                        ) p JOIN recording_audio_cleanup current ON current.recording_id = p.recording_id
                        WHERE p.desired_scope <> 'DORMANT' AND current.scope <> 'ALL'
                            AND current.scope <> p.desired_scope
                        ORDER BY p.recording_id LIMIT :limit
                        """,
                Long.class
        )
                .setParameter(
                        "limit",
                        limit
                )
                .getResultList();
    }

    @Override
    public RecordingAudioCleanupPolicy policy(long recordingId) {
        Object[] result = (Object[]) entityManager.createNativeQuery(POLICY_SQL + " WHERE t.recording_id = :id")
                .setParameter(
                        "id",
                        recordingId
                )
                .getSingleResult();
        Instant expiry = toInstant(result[2]);
        return new RecordingAudioCleanupPolicy(
                RecordingAudioCleanupScope.valueOf((String) result[1]),
                expiry
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findReady(
            Instant now,
            int limit
    ) {
        return entityManager.createNativeQuery(
                "SELECT current.recording_id FROM (" + POLICY_SQL + """
                        ) p JOIN recording_audio_cleanup current ON current.recording_id = p.recording_id
                        WHERE current.scope <> 'DORMANT' AND current.scope = p.desired_scope
                            AND current.next_attempt_at <= :now
                            AND (current.lease_until IS NULL OR current.lease_until <= :now)
                        ORDER BY current.next_attempt_at, current.recording_id LIMIT :limit
                        """,
                Long.class
        )
                .setParameter(
                        "now",
                        now
                )
                .setParameter(
                        "limit",
                        limit
                )
                .getResultList();
    }

    @Override
    public Optional<RecordingAudioCleanup> findForUpdate(long recordingId) {
        return Optional.ofNullable(
                entityManager.find(
                        RecordingAudioCleanup.class,
                        recordingId,
                        LockModeType.PESSIMISTIC_WRITE
                )
        );
    }
    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
    }
}
