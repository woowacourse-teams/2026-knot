package com.knot.backend.workspace.application;

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
    public long create(
            long memberId,
            String name
    ) {
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        name,
                        Instant.now(clock)
                )
        );
        workspaceMemberRepository.save(workspace.createOwnerMembership(memberId));
        return workspace.getId();
    }

    @Transactional(readOnly = true)
    public WorkspaceDetailResult findDetail(
            Long workspaceId,
            Long memberId
    ) {
        validateWorkspaceId(workspaceId);
        WorkspaceDetailResult detail = workspaceDetailQuery.find(
                workspaceId,
                memberId
        )
                .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        if (!detail.isMember()) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return detail;
    }

    @Transactional(readOnly = true)
    public WorkspaceListResult findAllByMemberId(long memberId) {
        List<Workspace> workspaces = workspaceRepository.findAllByMemberId(memberId);
        Long lastViewedWorkspaceId = workspaceMemberRepository.findLastViewedByMemberId(memberId)
                .map(WorkspaceMember::getWorkspaceId)
                .filter(
                        workspaceId -> containsWorkspace(
                                workspaces,
                                workspaceId
                        )
                )
                .orElse(null);
        return WorkspaceListResult.from(
                lastViewedWorkspaceId,
                workspaces
        );
    }

    @Transactional
    public void updateLastViewed(
            long memberId,
            Long workspaceId
    ) {
        List<WorkspaceMember> workspaceMembers = workspaceMemberRepository.findAllByMemberIdForUpdate(memberId);
        WorkspaceMember targetMembership = findTargetMembership(
                workspaceMembers,
                workspaceId
        );
        workspaceMembers.forEach(WorkspaceMember::clearLastViewed);
        workspaceMemberRepository.saveAll(workspaceMembers);
        workspaceMemberRepository.flush();
        targetMembership.markLastViewed();
        workspaceMemberRepository.save(targetMembership);
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
