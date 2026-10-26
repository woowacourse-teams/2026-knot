package com.knot.backend.document.domain.repository;

import com.knot.backend.document.domain.model.DocumentGenerationBatch;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentGenerationBatchRepository {

    Optional<DocumentGenerationBatch> findByRecordingSessionIdForUpdate(long recordingSessionId);

    Optional<DocumentGenerationBatch> findByIdForUpdate(long batchId);

    DocumentGenerationBatch saveAndFlush(DocumentGenerationBatch batch);

    Optional<DocumentGenerationBatch> findByWorkspaceIdAndRecordingSessionId(
            long workspaceId,
            long recordingSessionId
    );

    List<DocumentGenerationBatch> findAllByWorkspaceIdAndRecordingSessionIdIn(
            long workspaceId,
            Collection<Long> recordingSessionIds
    );
}
