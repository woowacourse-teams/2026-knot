package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingTitleGeneration;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RecordingTitleGenerationJpaRepository extends JpaRepository<RecordingTitleGeneration, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingTitleGeneration> findWithLockByRecordingId(long recordingId);

    @Modifying
    @Query(value = """
            INSERT INTO recording_title_generations (recording_id, status, attempt_count, next_run_at, created_at)
            SELECT b.recording_session_id, 'QUEUED', 0, :now, :now
            FROM document_generation_batches b
            JOIN recording_sessions s ON s.id = b.recording_session_id
            WHERE b.topic_registration_state = 'TOPICS_REGISTERED' AND b.transcript_id IS NOT NULL
                AND s.status = 'ENDED'
                AND NOT EXISTS (SELECT 1 FROM recording_title_generations g
                    WHERE g.recording_id = b.recording_session_id)
            ORDER BY b.id
            LIMIT :limit
            ON CONFLICT (recording_id) DO NOTHING
            """, nativeQuery = true)
    int registerEligible(
            Instant now,
            int limit
    );

    @Query(value = """
            SELECT recording_id FROM recording_title_generations
            WHERE (status = 'QUEUED' AND next_run_at <= :now)
                OR (status = 'RUNNING' AND lease_until <= :now)
            ORDER BY next_run_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRunnableRecordingIds(
            Instant now,
            int limit
    );

    @Query(value = """
            SELECT t.content FROM document_generation_batches b
            JOIN transcripts t ON t.id = b.transcript_id
            WHERE b.recording_session_id = :recordingId AND b.topic_registration_state = 'TOPICS_REGISTERED'
            """, nativeQuery = true)
    Optional<String> findTranscript(long recordingId);
}
