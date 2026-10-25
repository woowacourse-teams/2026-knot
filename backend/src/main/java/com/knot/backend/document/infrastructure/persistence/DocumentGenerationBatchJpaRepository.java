package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface DocumentGenerationBatchJpaRepository extends JpaRepository<DocumentGenerationBatch, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentGenerationBatch> findByRecordingSessionId(long recordingSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM DocumentGenerationBatch b WHERE b.id = :batchId")
    Optional<DocumentGenerationBatch> findLockedById(long batchId);

    @Query("""
            SELECT b FROM DocumentGenerationBatch b
            WHERE b.recordingSessionId = :recordingSessionId AND EXISTS (
                SELECT r.id FROM RecordingSession r WHERE r.id = b.recordingSessionId AND r.workspaceId = :workspaceId
            )
            """)
    Optional<DocumentGenerationBatch> findByWorkspaceIdAndRecordingSessionId(
            long workspaceId,
            long recordingSessionId
    );
}
