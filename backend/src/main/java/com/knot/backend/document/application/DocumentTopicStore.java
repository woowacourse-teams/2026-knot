package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import java.time.Instant;
import java.util.List;

public interface DocumentTopicStore {

    int capture(
            long operationId,
            long workspaceId
    );

    List<DocumentTopicTarget> pending(
            long operationId,
            int limit
    );

    List<DocumentTopicTarget> targets(long operationId);

    List<DocumentTopicCandidate> categories(long operationId);

    void saveBatch(
            long operationId,
            long workspaceId,
            DocumentTopicPlan plan
    );

    void consolidate(
            long operationId,
            DocumentTopicPlan plan
    );

    void markChanged(
            long operationId,
            long documentId
    );

    void resolveCategory(
            long operationId,
            String key,
            long categoryId
    );

    void replaceRecovery(
            long workspaceId,
            long operationId,
            Instant now
    );

    void prune(long workspaceId);
}
