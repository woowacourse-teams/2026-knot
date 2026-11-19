package com.knot.backend.search.infrastructure.notion;

/**
 * 노션 MCP가 HTTP 401로 토큰을 거절했다. 호출한 쪽이 토큰을 한 번 갱신하고 다시 부른다.
 */
class NotionMcpUnauthorizedException extends RuntimeException {

    NotionMcpUnauthorizedException() {
        super(
                "NOTION_MCP_UNAUTHORIZED",
                null,
                false,
                false
        );
    }
}
