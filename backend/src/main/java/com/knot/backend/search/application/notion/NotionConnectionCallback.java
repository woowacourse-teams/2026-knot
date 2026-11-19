package com.knot.backend.search.application.notion;

/**
 * 노션이 돌려보낸 콜백 쿼리와 연결을 시작한 브라우저의 state 쿠키(browserState). 값이 없으면 null이다.
 */
public record NotionConnectionCallback(
        String state,
        String code,
        String error,
        String issuer,
        String browserState
) {

    @Override
    public String toString() {
        return "NotionConnectionCallback[error=" + error + "]";
    }
}
