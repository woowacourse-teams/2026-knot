package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationSource;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationTarget;
import java.util.List;
import java.util.Optional;

public interface DocumentTopicRestorationStore {

    Optional<DocumentTopicRestorationSource> findSourceForUpdate(long workspaceId);

    int capture(
            long operationId,
            long workspaceId,
            long sourceOperationId
    );

    List<DocumentTopicRestorationTarget> pending(
            long operationId,
            int limit
    );

    List<DocumentTopicRestorationTarget> targets(long operationId);

    int markPrepared(
            long operationId,
            long documentId
    );

    void consumeRecovery(
            long workspaceId,
            long versionId,
            long sourceOperationId
    );

    void restoreCategoryAvailability(
            long workspaceId,
            long operationId,
            long sourceOperationId
    );
}
