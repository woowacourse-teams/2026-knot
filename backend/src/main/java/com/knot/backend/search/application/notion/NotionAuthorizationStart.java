package com.knot.backend.search.application.notion;

/**
 * 노션 동의 화면 주소와 그 주소에 담은 state. state는 연결을 시작한 브라우저에 쿠키로 묶어 콜백에서 대조한다.
 */
public record NotionAuthorizationStart(
        String authorizationUrl,
        String state
) {

    @Override
    public String toString() {
        return "NotionAuthorizationStart[]";
    }
}
