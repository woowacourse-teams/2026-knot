package com.knot.backend.workspace.application;

import com.knot.backend.workspace.application.dto.result.WorkspaceCreateResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceDetailResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceListResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceDetailQuery workspaceDetailQuery;
    private final Clock clock;

    @Transactional
    public WorkspaceCreateResult create(
            long memberId,
            String name
    ) {
        WorkspaceLog.Scope trace = WorkspaceLog.begin(
                "workspace.create",
                null
        );
        return trace.call(() -> {
            Workspace workspace = trace.step(
                    "create_workspace",
                    () -> Workspace.create(
                            name,
                            Instant.now(clock)
                    )
            );
            Workspace saved = trace.step(
                    "save_workspace",
                    () -> workspaceRepository.save(workspace)
            );
            trace.workspace(saved.getId());
            trace.step(
                    "save_owner_membership",
                    () -> workspaceMemberRepository.save(saved.createOwnerMembership(memberId))
            );
            trace.outcome("created");
            return new WorkspaceCreateResult(saved.getId());
        });
    }

    @Transactional(readOnly = true)
    public WorkspaceDetailResult findDetail(
            Long workspaceId,
            Long memberId
    ) {
        WorkspaceLog.Scope trace = WorkspaceLog.begin(
                "workspace.detail",
                workspaceId
        );
        return trace.call(() -> {
            trace.runStep(
                    "validate_workspace_id",
                    () -> validateWorkspaceId(workspaceId)
            );
            WorkspaceDetailResult detail = trace.step(
                    "load_detail",
                    () -> workspaceDetailQuery.find(
                            workspaceId,
                            memberId
                    )
                            .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND))
            );
            trace.runStep(
                    "validate_membership",
                    () -> {
                        if (!detail.isMember()) {
                            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
                        }
                    }
            );
            trace.outcome("completed");
            return detail;
        });
    }

    @Transactional(readOnly = true)
    public WorkspaceListResult findAllByMemberId(long memberId) {
        WorkspaceLog.Scope trace = WorkspaceLog.begin(
                "workspace.list",
                null
        );
        return trace.call(() -> {
            List<Workspace> workspaces = trace.step(
                    "load_workspaces",
                    () -> workspaceRepository.findAllByMemberId(memberId)
            );
            Long lastViewedWorkspaceId = trace.step(
                    "load_last_viewed",
                    () -> workspaceMemberRepository.findLastViewedByMemberId(memberId)
                            .map(WorkspaceMember::getWorkspaceId)
                            .filter(
                                    workspaceId -> containsWorkspace(
                                            workspaces,
                                            workspaceId
                                    )
                            )
                            .orElse(null)
            );
            WorkspaceListResult result = WorkspaceListResult.from(
                    lastViewedWorkspaceId,
                    workspaces
            );
            trace.outcome("completed");
            return result;
        });
    }

    @Transactional
    public void updateLastViewed(
            long memberId,
            Long workspaceId
    ) {
        WorkspaceLog.Scope trace = WorkspaceLog.begin(
                "workspace.last_viewed",
                workspaceId
        );
        trace.run(() -> {
            List<WorkspaceMember> workspaceMembers = trace.step(
                    "lock_memberships",
                    () -> workspaceMemberRepository.findAllByMemberIdForUpdate(memberId)
            );
            WorkspaceMember targetMembership = trace.step(
                    "find_target_membership",
                    () -> findTargetMembership(
                            workspaceMembers,
                            workspaceId
                    )
            );
            boolean replayed = targetMembership.isLastViewed();
            trace.runStep(
                    "clear_previous_selection",
                    () -> {
                        workspaceMembers.forEach(WorkspaceMember::clearLastViewed);
                        workspaceMemberRepository.saveAll(workspaceMembers);
                        workspaceMemberRepository.flush();
                    }
            );
            trace.runStep(
                    "save_selection",
                    () -> {
                        targetMembership.markLastViewed();
                        workspaceMemberRepository.save(targetMembership);
                    }
            );
            trace.outcome(lastViewedOutcome(replayed));
        });
    }

    private String lastViewedOutcome(boolean replayed) {
        if (replayed) {
            return "replayed";
        }
        return "changed";
    }

    private boolean containsWorkspace(
            List<Workspace> workspaces,
            Long workspaceId
    ) {
        return workspaces.stream()
                .anyMatch(
                        workspace -> workspace.getId()
                                .equals(workspaceId)
                );
    }

    private WorkspaceMember findTargetMembership(
            List<WorkspaceMember> workspaceMembers,
            Long workspaceId
    ) {
        return workspaceMembers.stream()
                .filter(
                        workspaceMember -> workspaceMember.getWorkspaceId()
                                .equals(workspaceId)
                )
                .findFirst()
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
    }

    private void validateWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
        }
    }
}
