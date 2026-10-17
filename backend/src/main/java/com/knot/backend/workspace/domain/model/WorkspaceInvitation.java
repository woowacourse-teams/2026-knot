package com.knot.backend.workspace.domain.model;

import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Getter;

@Getter
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitation {
    public static final int MAX_HASH_LENGTH = 255;
    public static final Duration VALIDITY_PERIOD = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "link_token_hash", nullable = false, updatable = false, length = MAX_HASH_LENGTH)
    private String linkTokenHash;

    @Column(name = "invite_code_hash", nullable = false, updatable = false, length = MAX_HASH_LENGTH)
    private String inviteCodeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected WorkspaceInvitation() {}

    private WorkspaceInvitation(
            Long workspaceId,
            String linkTokenHash,
            String inviteCodeHash,
            Instant createdAt
    ) {
        validateWorkspaceId(workspaceId);
        validateLinkTokenHash(linkTokenHash);
        validateInviteCodeHash(inviteCodeHash);
        validateCreatedAt(createdAt);
        this.workspaceId = workspaceId;
        this.linkTokenHash = linkTokenHash;
        this.inviteCodeHash = inviteCodeHash;
        Instant databasePrecisionCreatedAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = databasePrecisionCreatedAt.plus(VALIDITY_PERIOD);
        this.createdAt = databasePrecisionCreatedAt;
    }

    public static WorkspaceInvitation create(
            Long workspaceId,
            String linkTokenHash,
            String inviteCodeHash,
            Instant createdAt
    ) {
        return new WorkspaceInvitation(
                workspaceId,
                linkTokenHash,
                inviteCodeHash,
                createdAt
        );
    }

    public void validateUsableAt(Instant pointInTime) {
        if (!isUsableAt(pointInTime)) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_INVITATION_PREVIEW_NOT_FOUND);
        }
    }

    public WorkspaceMember createMembership(
            long memberId,
            Instant joinedAt
    ) {
        validateUsableAt(joinedAt);
        return WorkspaceMember.create(
                workspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                joinedAt
        );
    }

    private boolean isUsableAt(Instant pointInTime) {
        validatePointInTime(pointInTime);
        boolean issued = !pointInTime.isBefore(createdAt);
        boolean unexpired = pointInTime.isBefore(expiresAt);
        boolean notInvalidated = invalidatedAt == null;
        return issued && unexpired && notInvalidated;
    }

    private void validateWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
        }
    }

    private void validateLinkTokenHash(String linkTokenHash) {
        if (linkTokenHash == null || linkTokenHash.isBlank() || linkTokenHash.length() > MAX_HASH_LENGTH) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_INVITATION_LINK_TOKEN_HASH);
        }
    }

    private void validateInviteCodeHash(String inviteCodeHash) {
        if (inviteCodeHash == null || inviteCodeHash.isBlank() || inviteCodeHash.length() > MAX_HASH_LENGTH) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_INVITATION_CODE_HASH);
        }
    }

    private void validateCreatedAt(Instant createdAt) {
        if (createdAt == null) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_INVITATION_CREATED_AT);
        }
    }

    private void validatePointInTime(Instant pointInTime) {
        if (pointInTime == null) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_INVITATION_POINT_IN_TIME);
        }
    }
}
