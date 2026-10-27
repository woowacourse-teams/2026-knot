package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentGenerationInputQuery {

    Optional<DocumentGenerationInputResult> find(
            long workspaceId,
            long transcriptId
    );

    List<DocumentGenerationInputResult> findAll(
            long workspaceId,
            Collection<Long> transcriptIds
    );

    Optional<DocumentGenerationInputResult> findForUpdate(
            long workspaceId,
            long transcriptId
    );
}
