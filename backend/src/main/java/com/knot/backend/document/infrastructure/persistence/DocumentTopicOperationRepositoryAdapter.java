package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentTopicOperationRepositoryAdapter implements DocumentTopicOperationRepository {

    private final DocumentTopicOperationJpaRepository repository;

    public DocumentTopicOperation save(DocumentTopicOperation operation) {
        return repository.saveAndFlush(operation);
    }

    public Optional<DocumentTopicOperation> findByWorkspaceIdAndRequestId(
            long workspaceId,
            UUID requestId
    ) {
        return repository.findByWorkspaceIdAndRequestId(
                workspaceId,
                requestId
        );
    }

    public Optional<DocumentTopicOperation> lock(
            long workspaceId,
            long id
    ) {
        return repository.lock(
                workspaceId,
                id
        );
    }

    public boolean hasActive(long workspaceId) {
        return repository.hasActive(workspaceId);
    }

    public List<DocumentTopicOperation> findDispatchable(Instant now) {
        return repository.findDispatchable(
                now,
                PageRequest.of(
                        0,
                        32
                )
        );
    }
}
