package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.NotionConnection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface NotionConnectionJpaRepository extends JpaRepository<NotionConnection, Long> {

    Optional<NotionConnection> findByWorkspaceId(long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from NotionConnection connection where connection.workspaceId = :workspaceId")
    Optional<NotionConnection> findByWorkspaceIdForUpdate(long workspaceId);
}
