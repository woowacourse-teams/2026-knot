package com.knot.backend.document.domain.repository;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentTopicOperationRepository {

    DocumentTopicOperation save(DocumentTopicOperation operation);

    Optional<DocumentTopicOperation> findByWorkspaceIdAndRequestId(
            long workspaceId,
            UUID requestId
    );

    Optional<DocumentTopicOperation> lock(
            long workspaceId,
            long id
    );

    boolean hasActive(long workspaceId);

    List<DocumentTopicOperation> findDispatchable(Instant now);

    List<DocumentTopicOperation> findCancellationRequested();
}
