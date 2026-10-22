package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.PreparedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreparedDocumentJpaRepository extends JpaRepository<PreparedDocument, Long> {}
