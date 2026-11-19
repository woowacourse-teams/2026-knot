package com.knot.backend.search.infrastructure.persistence.notion;

import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotionConnectionRepositoryAdapter implements NotionConnectionRepository {

    private final NotionConnectionJpaRepository connections;

    @Override
    public NotionConnection save(NotionConnection connection) {
        return connections.save(connection);
    }

    @Override
    public Optional<NotionConnection> findByWorkspaceId(long workspaceId) {
        return connections.findByWorkspaceId(workspaceId);
    }

    @Override
    public Optional<NotionConnection> findByWorkspaceIdForUpdate(long workspaceId) {
        return connections.findByWorkspaceIdForUpdate(workspaceId);
    }

    @Override
    public void delete(NotionConnection connection) {
        connections.delete(connection);
    }

    @Override
    public void flush() {
        connections.flush();
    }
}
