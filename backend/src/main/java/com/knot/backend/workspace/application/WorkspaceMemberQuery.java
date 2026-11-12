package com.knot.backend.workspace.application;
import com.knot.backend.workspace.application.dto.result.WorkspaceMemberPage;
import com.knot.backend.workspace.domain.model.WorkspaceMemberCursor;
import java.util.List;
public interface WorkspaceMemberQuery {
    List<WorkspaceMemberPage.Item> find(
            long workspaceId,
            WorkspaceMemberCursor cursor,
            int limit
    );
}
