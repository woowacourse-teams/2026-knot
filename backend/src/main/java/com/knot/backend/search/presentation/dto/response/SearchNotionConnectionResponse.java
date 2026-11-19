package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.search.application.notion.NotionConnectionResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record SearchNotionConnectionResponse(
        @Schema(description = "NOT_CONNECTED(연결 없음), CONNECTED(연결됨), EXPIRED(만료)", allowableValues = {
                "NOT_CONNECTED", "CONNECTED", "EXPIRED"}, requiredMode = REQUIRED) String status,
        @Schema(description = "연결한 노션 워크스페이스 이름. NOT_CONNECTED면 null", nullable = true, requiredMode = REQUIRED) String notionWorkspaceName,
        @Schema(description = "노션 워크스페이스 아이콘(이모지 또는 이미지 주소). 없거나 NOT_CONNECTED면 null", nullable = true, requiredMode = REQUIRED) String notionWorkspaceIcon,
        @Schema(description = "연결한 사람. NOT_CONNECTED면 null", nullable = true, requiredMode = REQUIRED) ConnectedBy connectedBy,
        @Schema(description = "요청한 Member가 끊을 수 있는지. 연결한 사람이거나 OWNER면 true", requiredMode = REQUIRED) boolean canDisconnect
) {

    public static SearchNotionConnectionResponse from(NotionConnectionResult result) {
        return new SearchNotionConnectionResponse(
                result.status(),
                result.notionWorkspaceName(),
                result.notionWorkspaceIcon(),
                ConnectedBy.from(result.connectedBy()),
                result.canDisconnect()
        );
    }

    @Schema(name = "SearchNotionConnectedBy")
    public record ConnectedBy(
            @Schema(description = "연결한 사람의 Member ID", requiredMode = REQUIRED) long memberId,
            @Schema(description = "연결한 사람의 닉네임", requiredMode = REQUIRED) String nickname
    ) {

        static ConnectedBy from(NotionConnectionResult.ConnectedBy connectedBy) {
            if (connectedBy == null) {
                return null;
            }
            return new ConnectedBy(
                    connectedBy.memberId(),
                    connectedBy.nickname()
            );
        }
    }
}
