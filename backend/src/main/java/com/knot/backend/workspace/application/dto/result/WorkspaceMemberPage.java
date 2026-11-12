package com.knot.backend.workspace.application.dto.result;
import java.time.Instant;
import java.util.List;
public record WorkspaceMemberPage(
        List<Item> items,
        String nextCursor
) {
    public record Item(
            long memberId,
            String nickname,
            String profileImageUrl,
            String role,
            Instant joinedAt
    ) {
    }
}
