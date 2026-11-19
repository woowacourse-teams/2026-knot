package com.knot.backend.search.application.notion;

import java.util.Optional;

/**
 * 노션 페이지 만들기 요청의 위치 확인 결과. 찾은 위치와 카드를 만들지 않는 사유 가운데 한쪽에 값이 있다.
 */
public record SearchNotionWritePlan(
        Optional<SearchNotionWriteSkip> skip,
        Optional<SearchNotionLocation> location
) {

    public static SearchNotionWritePlan skipped(SearchNotionWriteSkip skip) {
        return new SearchNotionWritePlan(
                Optional.of(skip),
                Optional.empty()
        );
    }

    public static SearchNotionWritePlan ready(SearchNotionLocation location) {
        return new SearchNotionWritePlan(
                Optional.empty(),
                Optional.of(location)
        );
    }
}
