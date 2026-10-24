package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicRestorationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationSource;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicRestorationService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicRestorationStore store;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentTopicRestorationResult start(
            long workspaceId,
            long memberId,
            UUID requestId,
            long versionId
    ) {
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        requireOwner(
                workspace,
                memberId
        );
        Optional<DocumentTopicOperation> previous = operations.findByWorkspaceIdAndRequestId(
                workspaceId,
                requestId
        );
        if (previous.isPresent()) {
            return replay(
                    previous.orElseThrow(this::conflict),
                    versionId
            );
        }
        if (operations.hasActive(workspaceId)) {
            throw new DocumentException(DocumentErrorCode.TOPIC_OPERATION_IN_PROGRESS);
        }
        DocumentTopicRestorationSource source = store.findSourceForUpdate(workspaceId)
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.TOPIC_RESTORATION_NOT_AVAILABLE));
        if (source.versionId() != versionId) {
            throw new DocumentException(DocumentErrorCode.TOPIC_RESTORATION_VERSION_CONFLICT);
        }
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        DocumentTopicOperation operation = operations.save(
                DocumentTopicOperation.restoration(
                        workspaceId,
                        memberId,
                        requestId,
                        versionId,
                        source.sourceOperationId(),
                        now
                )
        );
        int count = store.capture(
                operation.getId(),
                workspaceId,
                source.sourceOperationId()
        );
        if (count != source.changedCount() || count == 0) {
            throw new DocumentException(DocumentErrorCode.INVALID_TOPIC_RESTORATION_DATA);
        }
        operation.capture(count);
        return DocumentTopicRestorationResult.from(
                operation,
                false
        );
    }

    private DocumentTopicRestorationResult replay(
            DocumentTopicOperation operation,
            long versionId
    ) {
        if (operation.getKind() != DocumentTopicOperationKind.RESTORE || !Objects.equals(
                operation.getTargetVersionId(),
                versionId
        )) {
            throw conflict();
        }
        return DocumentTopicRestorationResult.from(
                operation,
                true
        );
    }

    private void requireOwner(
            Workspace workspace,
            long memberId
    ) {
        if (workspace.isDeleted()) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!members.existsByWorkspaceIdAndMemberId(
                workspace.getId(),
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (!members.existsByWorkspaceIdAndMemberIdAndRole(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED);
        }
    }

    private DocumentException conflict() {
        return new DocumentException(DocumentErrorCode.TOPIC_REQUEST_ID_CONFLICT);
    }
}
