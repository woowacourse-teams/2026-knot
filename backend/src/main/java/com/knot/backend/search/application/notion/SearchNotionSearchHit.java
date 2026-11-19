package com.knot.backend.search.application.notion;

/**
 * 검색 결과 하나. path는 위치 경로 한 줄(모르면 빈 문자열), highlight는 낱말이 나온 본문 조각(없으면 빈 문자열)이다.
 */
public record SearchNotionSearchHit(
        String id,
        String title,
        String url,
        SearchNotionObjectKind kind,
        String path,
        String highlight
) {
}
