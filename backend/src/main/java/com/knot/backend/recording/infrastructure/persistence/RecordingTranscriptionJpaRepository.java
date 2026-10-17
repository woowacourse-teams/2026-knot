package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RecordingTranscriptionJpaRepository extends JpaRepository<RecordingTranscription, Long> {
    Optional<RecordingTranscription> findByRecordingSessionId(long recordingSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordingTranscription> findWithLockByRecordingSessionId(long recordingSessionId);

    Optional<RecordingTranscription> findByStatus(TranscriptionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM RecordingTranscription t
            WHERE t.status = com.knot.backend.recording.domain.model.TranscriptionStatus.PROCESSING
            """)
    Optional<RecordingTranscription> findProcessingForUpdate();

    @Query(value = """
            SELECT * FROM recording_transcriptions
            WHERE status = 'QUEUED' AND next_run_at <= :now
            ORDER BY queued_at, id LIMIT 1 FOR UPDATE
            """, nativeQuery = true)
    Optional<RecordingTranscription> findNextQueuedForUpdate(java.time.Instant now);

    // 639 is the shared admission lock for the single Mac mini inference slot.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(639)", nativeQuery = true)
    int lockAdmission();
}
