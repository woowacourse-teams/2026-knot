package com.knot.backend.workspace.domain.model;

import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.regex.Pattern;
import lombok.Getter;

@Getter
@Entity
@Table(name = "workspaces")
public class Workspace {
    public static final int MAX_NAME_LENGTH = 20;
    public static final String NAME_PATTERN_REGEX = "^[가-힣A-Za-z0-9 ]+$";
    private static final Pattern NAME_PATTERN = Pattern.compile(NAME_PATTERN_REGEX);
    private static final long LAST_ACTIVE_MEMBER_COUNT = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Workspace() {}

    private Workspace(
            String name,
            Instant createdAt
    ) {
        validateName(name);
        validateCreatedAt(createdAt);
        this.name = name;
        this.createdAt = createdAt;
        this.deletedAt = null;
    }

    public static Workspace create(
            String name,
            Instant createdAt
    ) {
        return new Workspace(
                name,
                createdAt
        );
    }

    public WorkspaceMember createOwnerMembership(long memberId) {
        return WorkspaceMember.create(
                id,
                memberId,
                WorkspaceMemberRole.OWNER,
                createdAt
        );
    }

    public void removeMember(
            WorkspaceMember member,
            long activeMemberCount,
            Instant leftAt
    ) {
        if (!member.isActive()) {
            return;
        }
        member.leave(
                leftAt,
                activeMemberCount
        );
        if (activeMemberCount == LAST_ACTIVE_MEMBER_COUNT) {
            delete(leftAt);
        }
    }

    public void delete(Instant deletedAt) {
        validateDeletedAt(deletedAt);
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || !NAME_PATTERN.matcher(name)
                .matches()) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_NAME);
        }
    }

    private void validateCreatedAt(Instant createdAt) {
        if (createdAt == null) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_CREATED_AT);
        }
    }

    private void validateDeletedAt(Instant deletedAt) {
        if (deletedAt == null || deletedAt.isBefore(createdAt)) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_DELETED_AT);
        }
    }
}
