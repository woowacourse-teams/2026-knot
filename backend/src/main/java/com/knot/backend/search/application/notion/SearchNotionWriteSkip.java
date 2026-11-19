package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;

/**
 * 노션 페이지 만들기 요청에서 초안과 카드를 만들지 않는 사유(SEARCH-R45, R47).
 */
public enum SearchNotionWriteSkip {
    LOCATION_MISSING,
    NOT_CONNECTED,
    CONNECTION_EXPIRED,
    MEETING_NOT_FOUND,
    MULTIPLE_MEETINGS,
    MULTIPLE_PAGES,
    LOCATION_NOT_FOUND,
    LOCATION_FAILED;

    public static SearchNotionWriteSkip from(SearchNotionNoticeReason reason) {
        if (reason == SearchNotionNoticeReason.CONNECTION_EXPIRED) {
            return CONNECTION_EXPIRED;
        }
        return NOT_CONNECTED;
    }

    public String guidance() {
        return switch (this) {
            case LOCATION_MISSING -> SearchNotionGuidance.LOCATION_MISSING;
            case NOT_CONNECTED -> SearchNotionGuidance.NOT_CONNECTED;
            case CONNECTION_EXPIRED -> SearchNotionGuidance.CONNECTION_EXPIRED;
            case MEETING_NOT_FOUND -> SearchNotionGuidance.MEETING_NOT_FOUND;
            case MULTIPLE_MEETINGS -> SearchNotionGuidance.MULTIPLE_MEETINGS;
            case MULTIPLE_PAGES -> SearchNotionGuidance.MULTIPLE_PAGES;
            case LOCATION_NOT_FOUND -> SearchNotionGuidance.LOCATION_NOT_FOUND;
            case LOCATION_FAILED -> SearchNotionGuidance.LOCATION_FAILED;
        };
    }
}
