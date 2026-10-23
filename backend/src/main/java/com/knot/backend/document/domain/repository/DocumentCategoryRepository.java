package com.knot.backend.document.domain.repository;

import com.knot.backend.document.domain.model.DocumentCategory;
import java.util.List;

public interface DocumentCategoryRepository {

    List<DocumentCategory> findAllByWorkspaceId(long workspaceId);

    DocumentCategory save(DocumentCategory category);
}
