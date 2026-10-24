package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationSnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicRecoverySnapshot;
import java.util.Optional;

public interface DocumentTopicStatusQuery {

    Optional<DocumentTopicOperationSnapshot> findOperation(long workspaceId);

    Optional<DocumentTopicRecoverySnapshot> findRestorableVersion(long workspaceId);
}
