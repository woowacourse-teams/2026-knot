package com.knot.backend.search.application.notion;

/**
 * 노션 페이지 만들기 요청에서 카드를 만들지 않을 때 보내는 안내 답글(SEARCH-R45, R47).
 */
public final class SearchNotionGuidance {
    public static final String LOCATION_MISSING = "노션 어디에 만들지 알려 주세요. 페이지나 DB 이름을 함께 말씀해 주시면 초안을 준비할게요.";
    public static final String NOT_CONNECTED = "노션 연결이 없어 페이지를 만들 수 없어요. 노션을 연결한 뒤 다시 요청해 주세요.";
    public static final String CONNECTION_EXPIRED = "노션 연결이 만료되어 페이지를 만들 수 없어요. 노션을 다시 연결한 뒤 요청해 주세요.";
    public static final String MEETING_NOT_FOUND = "노션 페이지로 만들 회의 문서를 찾지 못했어요. 회의 제목이나 날짜를 알려 주세요.";
    public static final String MULTIPLE_MEETINGS = "요청에 맞는 회의가 여러 건이에요. 어느 회의로 만들지 회의 제목이나 날짜를 알려 주세요.";
    public static final String MULTIPLE_PAGES = "노션 페이지는 한 번에 하나씩 만들 수 있어요. 회의 문서를 한 건씩 요청해 주세요.";
    public static final String LOCATION_NOT_FOUND = "노션에서 말씀하신 위치를 찾지 못했어요. 페이지나 DB 이름을 더 자세히 알려 주세요.";
    public static final String LOCATION_FAILED = "지금은 노션에서 위치를 찾지 못했어요. 잠시 뒤 다시 요청해 주세요.";

    private SearchNotionGuidance() {}
}
