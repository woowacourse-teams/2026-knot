package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface DocumentTopicOperationJpaRepository extends JpaRepository<DocumentTopicOperation, Long> {

    Optional<DocumentTopicOperation> findByWorkspaceIdAndRequestId(
            long workspaceId,
            UUID requestId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DocumentTopicOperation o where o.workspaceId = :workspaceId and o.id = :id")
    Optional<DocumentTopicOperation> lock(
            long workspaceId,
            long id
    );

    @Query("select count(o) > 0 from DocumentTopicOperation o where o.workspaceId = :workspaceId and o.status in ('QUEUED','RUNNING','CANCEL_REQUESTED','APPLYING')")
    boolean hasActive(long workspaceId);

    @Query("""
            select o from DocumentTopicOperation o where
              (o.status = 'QUEUED' and o.nextAttemptAt <= :now)
              or (o.status = 'RUNNING' and o.leaseUntil <= :now)
              or (o.status in ('QUEUED','RUNNING') and o.deadlineAt <= :now)
            order by o.createdAt
            """)
    List<DocumentTopicOperation> findDispatchable(
            Instant now,
            Pageable pageable
    );
}
