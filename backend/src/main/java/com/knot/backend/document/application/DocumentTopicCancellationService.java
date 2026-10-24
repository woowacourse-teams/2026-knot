package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicCancellationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentTopicCancellationService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final DocumentTopicOperationRepository operations;
    private final DocumentTopicStore store;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentTopicCancellationResult request(
            long workspaceId,
            long memberId,
            long operationId
    ) {
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        requireOwner(
                workspace,
                memberId
        );
        DocumentTopicOperation operation = operations.lock(
                workspaceId,
                operationId
        )
                .orElseThrow(() -> new DocumentException(DocumentErrorCode.TOPIC_OPERATION_NOT_FOUND));
        operation.requestCancellation(
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
        operations.save(operation);
        return DocumentTopicCancellationResult.from(operation);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(
            long workspaceId,
            long operationId
    ) {
        if (workspaces.findIncludingDeletedByIdForUpdate(workspaceId)
                .isEmpty()) {
            return;
        }
        DocumentTopicOperation operation = operations.lock(
                workspaceId,
                operationId
        )
                .orElse(null);
        if (operation == null) {
            return;
        }
        if (operation.getStatus() != DocumentTopicOperationStatus.CANCEL_REQUESTED) {
            return;
        }
        operation.completeCancellation(
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
        operations.save(operation);
        store.prune(workspaceId);
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
}
