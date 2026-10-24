package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentTopicOperationSnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicRecoverySnapshot;
import com.knot.backend.document.application.dto.result.DocumentTopicStatusResult;
import com.knot.backend.document.application.dto.result.DocumentTopicStatusOperationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicFailureResult;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DocumentTopicStatusService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final DocumentTopicStatusQuery query;

    public DocumentTopicStatusResult find(
            long workspaceId,
            long memberId
    ) {
        requireOwner(
                workspaceId,
                memberId
        );
        Optional<DocumentTopicOperationSnapshot> operation = query.findOperation(workspaceId);
        Optional<DocumentTopicRecoverySnapshot> recovery = query.findRestorableVersion(workspaceId);
        boolean active = operation.map(item -> isActive(item.status()))
                .orElse(false);
        return new DocumentTopicStatusResult(
                workspaceId,
                operation.map(this::operationResult)
                        .orElse(null),
                recovery.orElse(null),
                recovery.isPresent() && !active
        );
    }

    private void requireOwner(
            long workspaceId,
            long memberId
    ) {
        workspaces.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        if (!members.existsByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (!members.existsByWorkspaceIdAndMemberIdAndRole(
                workspaceId,
                memberId,
                WorkspaceMemberRole.OWNER
        )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED);
        }
    }

    private DocumentTopicStatusOperationResult operationResult(DocumentTopicOperationSnapshot item) {
        return new DocumentTopicStatusOperationResult(
                item.operationId(),
                type(item.kind()),
                item.status()
                        .reportedStatus(item.retryCount()),
                null,
                item.targetCount(),
                item.processedCount(),
                changedCount(item),
                cancellable(item.status()),
                item.createdAt(),
                item.startedAt(),
                item.finishedAt(),
                failure(item)
        );
    }

    private Long changedCount(DocumentTopicOperationSnapshot item) {
        if (item.status() == DocumentTopicOperationStatus.SUCCEEDED) {
            return item.changedCount();
        }
        return null;
    }

    private DocumentTopicFailureResult failure(DocumentTopicOperationSnapshot item) {
        if (item.status() != DocumentTopicOperationStatus.FAILED) {
            return null;
        }
        if (item.kind() == DocumentTopicOperationKind.RESTORE) {
            return new DocumentTopicFailureResult(
                    "TOPIC_RESTORATION_FAILED",
                    "직전 정리를 되돌리지 못해 현재 분류를 유지했어요. 다시 시도해 주세요."
            );
        }
        return new DocumentTopicFailureResult(
                "TOPIC_REORGANIZATION_FAILED",
                "주제를 정리하지 못해 기존 분류를 유지했어요. 다시 시도해 주세요."
        );
    }

    private String type(DocumentTopicOperationKind kind) {
        if (kind == DocumentTopicOperationKind.RESTORE) {
            return "RESTORATION";
        }
        return "REORGANIZATION";
    }

    private boolean cancellable(DocumentTopicOperationStatus status) {
        return status == DocumentTopicOperationStatus.QUEUED || status == DocumentTopicOperationStatus.RUNNING;
    }

    private boolean isActive(DocumentTopicOperationStatus status) {
        return cancellable(status) || status == DocumentTopicOperationStatus.APPLYING
                || status == DocumentTopicOperationStatus.CANCEL_REQUESTED;
    }
}
