package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.recording.domain.model.Transcript;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface DocumentGenerationInputJpaRepository extends Repository<Transcript, Long> {

    @Query("""
            SELECT t FROM Transcript t WHERE t.id = :transcriptId AND EXISTS (
                SELECT rs.id FROM RecordingSession rs
                WHERE rs.id = t.recordingSessionId AND rs.workspaceId = :workspaceId
            )
            """)
    Optional<Transcript> findAvailableByWorkspaceIdAndId(
            long workspaceId,
            long transcriptId
    );

    @Query("""
            SELECT t FROM Transcript t WHERE t.id IN :transcriptIds AND EXISTS (
                SELECT rs.id FROM RecordingSession rs
                WHERE rs.id = t.recordingSessionId AND rs.workspaceId = :workspaceId
            )
            """)
    List<Transcript> findAllAvailableByWorkspaceIdAndIdIn(
            long workspaceId,
            Collection<Long> transcriptIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM Transcript t WHERE t.id = :transcriptId AND EXISTS (
                SELECT rs.id FROM RecordingSession rs
                WHERE rs.id = t.recordingSessionId AND rs.workspaceId = :workspaceId
            )
            """)
    Optional<Transcript> findByWorkspaceIdAndId(
            long workspaceId,
            long transcriptId
    );
}
