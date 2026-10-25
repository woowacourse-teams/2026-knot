package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentGenerationJob;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.knot.backend.document.domain.model.DocumentGenerationCandidate;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface DocumentGenerationJobJpaRepository extends JpaRepository<DocumentGenerationJob, Long> {

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.knot.backend.document.domain.model.DocumentGenerationCandidate(rs.workspaceId, j.id, j.attemptCount)
            FROM DocumentGenerationJob j JOIN DocumentGenerationBatch b ON b.id = j.batchId
            JOIN RecordingSession rs ON rs.id = b.recordingSessionId
            WHERE j.status = com.knot.backend.document.domain.model.DocumentGenerationJobStatus.QUEUED
                AND j.nextAttemptAt <= :now
            ORDER BY j.nextAttemptAt, j.id
            """)
    List<DocumentGenerationCandidate> findReadyCandidates(
            Instant now,
            Pageable pageable
    );

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.knot.backend.document.domain.model.DocumentGenerationCandidate(rs.workspaceId, j.id, j.attemptCount)
            FROM DocumentGenerationJob j JOIN DocumentGenerationBatch b ON b.id = j.batchId
            JOIN RecordingSession rs ON rs.id = b.recordingSessionId
            WHERE j.status = com.knot.backend.document.domain.model.DocumentGenerationJobStatus.RUNNING
                AND j.executionDeadlineAt <= :now
            ORDER BY j.executionDeadlineAt, j.id
            """)
    List<DocumentGenerationCandidate> findExpiredCandidates(
            Instant now,
            Pageable pageable
    );

    List<DocumentGenerationJob> findAllByBatchIdOrderById(long batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT j FROM DocumentGenerationJob j
            WHERE j.id = :jobId AND EXISTS (
                SELECT t.id FROM Transcript t JOIN RecordingSession rs ON rs.id = t.recordingSessionId
                WHERE t.id = j.transcriptId AND rs.workspaceId = :workspaceId
            )
            """)
    Optional<DocumentGenerationJob> findByWorkspaceIdAndId(
            long workspaceId,
            long jobId
    );
}
