package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentTopicReorganizationService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicStore store;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentTopicOperationResult start(
            long workspaceId,
            long memberId,
            UUID requestId
    ) {
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));
        requireOwner(
                workspace,
                memberId
        );
        Optional<DocumentTopicOperation> previous = operations.findByWorkspaceIdAndRequestId(
                workspaceId,
                requestId
        );
        if (previous.isPresent()) {
            return replay(previous.orElseThrow(this::conflict));
        }
        if (operations.hasActive(workspaceId)) {
            throw conflict();
        }
        Instant now = clock.instant();
        DocumentTopicOperation operation = operations.save(
                DocumentTopicOperation.reorganize(
                        workspaceId,
                        memberId,
                        requestId,
                        now
                )
        );
        int count = store.capture(
                operation.getId(),
                workspaceId
        );
        operation.capture(count);
        return DocumentTopicOperationResult.from(
                operation,
                false
        );
    }

    private void requireOwner(
            Workspace workspace,
            long memberId
    ) {
        if (workspace.isDeleted() || !members.existsByWorkspaceIdAndMemberId(
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

    private DocumentTopicOperationResult replay(DocumentTopicOperation operation) {
        if (operation.getKind() != DocumentTopicOperationKind.REORGANIZE) {
            throw conflict();
        }
        return DocumentTopicOperationResult.from(
                operation,
                true
        );
    }

    private DocumentException conflict() {
        return new DocumentException(DocumentErrorCode.TOPIC_OPERATION_CONFLICT);
    }
}
