package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.knot.backend.document.domain.model.DocumentGenerationCandidate;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentGenerationJobRepositoryAdapter implements DocumentGenerationJobRepository {

    private final DocumentGenerationJobJpaRepository repository;

    @Override
    public DocumentGenerationJob save(DocumentGenerationJob job) {
        return repository.save(job);
    }

    @Override
    public List<DocumentGenerationJob> findAllByBatchId(long batchId) {
        return repository.findAllByBatchIdOrderById(batchId);
    }

    @Override
    public List<DocumentGenerationJob> findAllByBatchIdIn(Collection<Long> batchIds) {
        return repository.findAllByBatchIdInOrderById(batchIds);
    }

    @Override
    public Optional<DocumentGenerationJob> findByWorkspaceIdAndIdForUpdate(
            long workspaceId,
            long jobId
    ) {
        return repository.findByWorkspaceIdAndId(
                workspaceId,
                jobId
        );
    }

    @Override
    public void flush() {
        repository.flush();
    }

    @Override
    public List<DocumentGenerationCandidate> findReadyCandidates(
            Instant now,
            int limit
    ) {
        return repository.findReadyCandidates(
                now,
                PageRequest.of(
                        0,
                        limit
                )
        );
    }

    @Override
    public List<DocumentGenerationCandidate> findExpiredCandidates(
            Instant now,
            int limit
    ) {
        return repository.findExpiredCandidates(
                now,
                PageRequest.of(
                        0,
                        limit
                )
        );
    }
}
