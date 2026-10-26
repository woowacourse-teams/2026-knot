package com.knot.backend.document.domain.repository;

import com.knot.backend.document.domain.model.DocumentGenerationCandidate;
import com.knot.backend.document.domain.model.DocumentGenerationJob;

import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.time.Instant;

public interface DocumentGenerationJobRepository {

    DocumentGenerationJob save(DocumentGenerationJob job);

    List<DocumentGenerationJob> findAllByBatchId(long batchId);

    List<DocumentGenerationJob> findAllByBatchIdIn(Collection<Long> batchIds);

    Optional<DocumentGenerationJob> findByWorkspaceIdAndIdForUpdate(
            long workspaceId,
            long jobId
    );

    void flush();

    List<DocumentGenerationCandidate> findReadyCandidates(
            Instant now,
            int limit
    );

    List<DocumentGenerationCandidate> findExpiredCandidates(
            Instant now,
            int limit
    );
}
