package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface DocumentJpaRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByDocumentGenerationJobId(long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Document> findByWorkspaceIdAndId(
            long workspaceId,
            long documentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d FROM Document d
            WHERE d.workspaceId = :workspaceId AND d.status = :status
              AND EXISTS (SELECT c.id FROM DocumentConfirmation c
                  WHERE c.id.documentId = d.id AND c.id.memberId = :memberId AND c.confirmedAt IS NULL)
            ORDER BY d.id
            """)
    List<Document> findAffectedDrafts(
            long workspaceId,
            long memberId,
            DocumentStatus status
    );
}
