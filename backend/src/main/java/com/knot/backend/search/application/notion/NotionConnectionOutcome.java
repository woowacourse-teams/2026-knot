package com.knot.backend.search.application.notion;

/**
 * 노션 동의 화면에서 돌아온 결과. 값 이름이 화면 주소의 notionConnection 쿼리 값이다.
 */
public enum NotionConnectionOutcome {
    CONNECTED,
    NOTION_CONNECTION_ALREADY_CONNECTED,
    CANCELLED,
    FAILED
}
