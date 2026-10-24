package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import java.util.Optional;

public interface DocumentGenerationInputQuery {

    Optional<DocumentGenerationInputResult> find(
            long workspaceId,
            long transcriptId
    );

    Optional<DocumentGenerationInputResult> findForUpdate(
            long workspaceId,
            long transcriptId
    );
}
