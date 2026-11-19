package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import java.util.Optional;

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

    /**
     * 연결이 없거나 만료면 화면이 [연결]과 [다시 연결]을 보일 수 있게 노션 안내도 저장한다(정본 미정, 기본값).
     */
    public Optional<SearchNotionNoticeReason> notice() {
        return switch (this) {
            case NOT_CONNECTED -> Optional.of(SearchNotionNoticeReason.NOT_CONNECTED);
            case CONNECTION_EXPIRED -> Optional.of(SearchNotionNoticeReason.CONNECTION_EXPIRED);
            default -> Optional.empty();
        };
    }
}
