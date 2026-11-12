package com.knot.backend.workspace.application;
import com.knot.backend.workspace.application.dto.result.WorkspaceMemberPage;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMemberCursor;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository memberships;
    private final WorkspaceMemberQuery query;
    @Transactional(readOnly = true)
    public WorkspaceMemberPage find(
            long workspaceId,
            long memberId,
            String cursor,
            int limit
    ) {
        if (workspaces.findById(workspaceId)
                .isEmpty()
                || !memberships.existsByWorkspaceIdAndMemberId(
                        workspaceId,
                        memberId
                )) {
            throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (limit < 1 || limit > 100) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_LIMIT);
        }
        WorkspaceMemberCursor parsed = null;
        if (cursor != null) {
            parsed = WorkspaceMemberCursor.parse(
                    cursor,
                    workspaceId
            );
        }
        List<WorkspaceMemberPage.Item> rows = query.find(
                workspaceId,
                parsed,
                limit + 1
        );
        if (rows.size() <= limit) {
            return new WorkspaceMemberPage(
                    rows,
                    null
            );
        }
        List<WorkspaceMemberPage.Item> page = List.copyOf(
                rows.subList(
                        0,
                        limit
                )
        );
        WorkspaceMemberPage.Item last = page.getLast();
        return new WorkspaceMemberPage(
                page,
                WorkspaceMemberCursor.of(
                        workspaceId,
                        last.joinedAt(),
                        last.memberId()
                )
                        .encode()
        );
    }
}
