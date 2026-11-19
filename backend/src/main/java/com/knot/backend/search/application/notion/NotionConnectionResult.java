package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.NotionConnection;

/**
 * Workspace의 노션 연결 조회 결과. status는 NOT_CONNECTED, CONNECTED, EXPIRED다.
 */
public record NotionConnectionResult(
        String status,
        String notionWorkspaceName,
        String notionWorkspaceIcon,
        ConnectedBy connectedBy,
        boolean canDisconnect
) {
    public static final String NOT_CONNECTED = "NOT_CONNECTED";

    public static NotionConnectionResult notConnected() {
        return new NotionConnectionResult(
                NOT_CONNECTED,
                null,
                null,
                null,
                false
        );
    }

    public static NotionConnectionResult of(
            NotionConnection connection,
            String nickname,
            boolean canDisconnect
    ) {
        return new NotionConnectionResult(
                connection.getStatus()
                        .name(),
                connection.getNotionWorkspaceName(),
                connection.getNotionWorkspaceIcon(),
                new ConnectedBy(
                        connection.getConnectedMemberId(),
                        nickname
                ),
                canDisconnect
        );
    }

    public record ConnectedBy(
            long memberId,
            String nickname
    ) {
    }
}
