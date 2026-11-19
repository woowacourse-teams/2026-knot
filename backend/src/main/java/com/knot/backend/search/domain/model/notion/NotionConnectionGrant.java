package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.time.Instant;

/**
 * 노션 동의 화면을 마친 뒤 받은 연결 값. 토큰은 암호화한 값만 담는다.
 */
public record NotionConnectionGrant(
        String notionWorkspaceId,
        String notionWorkspaceName,
        String notionWorkspaceIcon,
        String encryptedAccessToken,
        String encryptedRefreshToken,
        Instant accessTokenExpiresAt
) {

    public NotionConnectionGrant {
        if (isBlank(notionWorkspaceId) || isBlank(encryptedAccessToken)) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
