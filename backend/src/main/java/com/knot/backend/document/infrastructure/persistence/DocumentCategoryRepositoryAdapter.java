package com.knot.backend.document.infrastructure.persistence;

import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.repository.DocumentCategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DocumentCategoryRepositoryAdapter implements DocumentCategoryRepository {

    private final DocumentCategoryJpaRepository repository;

    @Override
    public List<DocumentCategory> findAllByWorkspaceId(long workspaceId) {
        return repository.findAllByWorkspaceIdOrderById(workspaceId);
    }

    @Override
    public DocumentCategory save(DocumentCategory category) {
        return repository.saveAndFlush(category);
    }
}
