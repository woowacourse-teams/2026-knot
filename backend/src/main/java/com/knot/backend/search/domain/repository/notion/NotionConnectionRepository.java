package com.knot.backend.search.domain.repository.notion;

import com.knot.backend.search.domain.model.notion.NotionConnection;
import java.util.Optional;

public interface NotionConnectionRepository {

    NotionConnection save(NotionConnection connection);

    Optional<NotionConnection> findByWorkspaceId(long workspaceId);

    Optional<NotionConnection> findByWorkspaceIdForUpdate(long workspaceId);

    void delete(NotionConnection connection);

    void flush();
}
