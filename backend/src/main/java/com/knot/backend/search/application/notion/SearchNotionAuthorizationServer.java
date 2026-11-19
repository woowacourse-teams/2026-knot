package com.knot.backend.search.application.notion;

/**
 * 노션 MCP OAuth 토큰 엔드포인트 포트. 실패는 {@link SearchNotionException}으로 알린다.
 */
public interface SearchNotionAuthorizationServer {

    /**
     * 동의 화면에서 받은 code를 토큰으로 바꾸고 고른 노션 워크스페이스를 함께 돌려준다.
     */
    SearchNotionIssuedGrant exchange(
            String code,
            String codeVerifier
    );
}
