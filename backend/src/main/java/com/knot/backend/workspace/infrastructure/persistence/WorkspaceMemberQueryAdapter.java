package com.knot.backend.workspace.infrastructure.persistence;
import com.knot.backend.workspace.application.WorkspaceMemberQuery;
import com.knot.backend.workspace.application.dto.result.WorkspaceMemberPage;
import com.knot.backend.workspace.domain.model.WorkspaceMemberCursor;
import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
@Repository
@RequiredArgsConstructor
public class WorkspaceMemberQueryAdapter implements WorkspaceMemberQuery {
    private final JdbcClient jdbc;
    @Override
    public List<WorkspaceMemberPage.Item> find(
            long workspaceId,
            WorkspaceMemberCursor cursor,
            int limit
    ) {
        String after = "";
        if (cursor != null) {
            after = " AND (wm.joined_at,wm.member_id)>(:joinedAt,:memberId)";
        }
        JdbcClient.StatementSpec sql = jdbc.sql("""
                SELECT m.id,m.nickname,m.profile_image_url,wm.role,wm.joined_at
                FROM workspace_members wm JOIN members m ON m.id=wm.member_id
                WHERE wm.workspace_id=:workspaceId AND wm.left_at IS NULL AND m.deleted_at IS NULL
                """ + after + " ORDER BY wm.joined_at,wm.member_id LIMIT :limit")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "limit",
                        limit
                );
        if (cursor != null) {
            sql.param(
                    "joinedAt",
                    Timestamp.from(cursor.getJoinedAt())
            )
                    .param(
                            "memberId",
                            cursor.getMemberId()
                    );
        }
        return sql.query(
                (
                        row,
                        index
                ) -> new WorkspaceMemberPage.Item(
                        row.getLong("id"),
                        row.getString("nickname"),
                        row.getString("profile_image_url"),
                        row.getString("role"),
                        row.getTimestamp("joined_at")
                                .toInstant()
                )
        )
                .list();
    }
}
