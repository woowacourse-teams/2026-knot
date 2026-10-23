package com.knot.backend.document.domain.repository;

import com.knot.backend.document.domain.model.PreparedDocument;
import java.util.Optional;

public interface PreparedDocumentRepository {

    PreparedDocument save(PreparedDocument document);

    Optional<PreparedDocument> findByJobId(long jobId);

    void delete(PreparedDocument document);
}
