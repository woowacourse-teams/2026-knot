package com.knot.backend.search.application.notion;

import java.time.Instant;

/**
 * 노션이 발급한 토큰 원문과 고른 노션 워크스페이스. 저장하기 전에 토큰을 암호화한다. toString에 토큰을 싣지 않는다.
 */
public record SearchNotionIssuedGrant(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        String notionWorkspaceId,
        String notionWorkspaceName,
        String notionWorkspaceIcon
) {

    @Override
    public String toString() {
        return "SearchNotionIssuedGrant[notionWorkspaceId=" + notionWorkspaceId + "]";
    }
}
