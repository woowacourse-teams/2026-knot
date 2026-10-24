package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentCategoryJpaRepository extends JpaRepository<DocumentCategory, Long> {

    List<DocumentCategory> findAllByWorkspaceIdOrderById(long workspaceId);
}
