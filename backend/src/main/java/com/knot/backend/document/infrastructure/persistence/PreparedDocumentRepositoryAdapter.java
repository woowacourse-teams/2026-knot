package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.PreparedDocument;
import com.knot.backend.document.domain.repository.PreparedDocumentRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PreparedDocumentRepositoryAdapter implements PreparedDocumentRepository {

    private final PreparedDocumentJpaRepository repository;

    @Override
    public PreparedDocument save(PreparedDocument document) {
        return repository.save(document);
    }

    @Override
    public Optional<PreparedDocument> findByJobId(long jobId) {
        return repository.findById(jobId);
    }

    @Override
    public void delete(PreparedDocument document) {
        repository.delete(document);
    }
}
