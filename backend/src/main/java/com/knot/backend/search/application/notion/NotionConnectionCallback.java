package com.knot.backend.search.application.notion;

/**
 * 노션이 돌려보낸 콜백 쿼리. 값이 없으면 null이다.
 */
public record NotionConnectionCallback(
        String state,
        String code,
        String error,
        String issuer
) {

    @Override
    public String toString() {
        return "NotionConnectionCallback[error=" + error + "]";
    }
}
