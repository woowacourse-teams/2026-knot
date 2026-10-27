package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentGenerationBatchRepositoryAdapter implements DocumentGenerationBatchRepository {

    private final DocumentGenerationBatchJpaRepository repository;

    @Override
    public Optional<DocumentGenerationBatch> findByRecordingSessionIdForUpdate(long recordingSessionId) {
        return repository.findByRecordingSessionId(recordingSessionId);
    }

    @Override
    public Optional<DocumentGenerationBatch> findByIdForUpdate(long batchId) {
        return repository.findLockedById(batchId);
    }

    @Override
    public DocumentGenerationBatch saveAndFlush(DocumentGenerationBatch batch) {
        return repository.saveAndFlush(batch);
    }

    @Override
    public Optional<DocumentGenerationBatch> findByWorkspaceIdAndRecordingSessionId(
            long workspaceId,
            long recordingSessionId
    ) {
        return repository.findByWorkspaceIdAndRecordingSessionId(
                workspaceId,
                recordingSessionId
        );
    }

    @Override
    public List<DocumentGenerationBatch> findAllByWorkspaceIdAndRecordingSessionIdIn(
            long workspaceId,
            Collection<Long> recordingSessionIds
    ) {
        return repository.findAllByWorkspaceIdAndRecordingSessionIdIn(
                workspaceId,
                recordingSessionIds
        );
    }
}
