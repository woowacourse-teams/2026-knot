package com.knot.backend.recording.infrastructure.persistence;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
interface RecordingAudioFinalizationJpaRepository extends JpaRepository<RecordingAudioFinalization, Long> {
    Optional<RecordingAudioFinalization> findByRecordingId(long recordingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingAudioFinalization> findWithLockByRecordingId(long recordingId);
    @Query(value = """
            SELECT s.recording_id FROM recording_audio_streams s
            JOIN recording_sessions r ON r.id = s.recording_id
            JOIN workspaces w ON w.id = r.workspace_id AND w.deleted_at IS NULL
            WHERE s.sealed_at IS NOT NULL AND NOT s.frozen AND r.status = 'ENDED'
                AND r.ended_at + INTERVAL '30 days' > CURRENT_TIMESTAMP
                AND EXISTS (SELECT 1 FROM workspace_members m
                    WHERE m.workspace_id = r.workspace_id AND m.member_id = r.member_id)
                AND NOT EXISTS (SELECT 1 FROM recording_audio_finalizations j WHERE j.recording_id = s.recording_id)
            ORDER BY s.sealed_at, s.recording_id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findUnregisteredSealedRecordingIds(int limit);
    @Query(value = """
            SELECT recording_id FROM recording_audio_finalizations
            WHERE (status = 'QUEUED' AND next_run_at <= :now)
                OR (status = 'PROCESSING' AND lease_until <= :now)
            ORDER BY next_run_at, id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRunnableRecordingIds(
            Instant now,
            int limit
    );
}
