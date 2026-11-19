package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.domain.model.SearchIntent;
import java.util.Optional;

/**
 * 분류 모델이 정한 사용 계획. intent는 회의 문서 찾기와 안내 여부를, 노션 도구는 계획에 있을 때만 값을 가진다.
 * notionCreateLocation이 빈 문자열이면 사용자가 만들 위치를 말하지 않았다.
 */
public record SearchUsePlan(
        SearchIntent intent,
        Optional<String> notionReadQuery,
        Optional<String> notionCreateLocation
) {

    public SearchUsePlan {
        if (intent == null || notionReadQuery == null || notionCreateLocation == null) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
        // 노션 페이지 만들기는 초안의 근거가 될 회의 문서를 함께 찾는다(SEARCH-R35).
        if (notionCreateLocation.isPresent()) {
            intent = SearchIntent.MEETING_SEARCH;
        }
    }

    public static SearchUsePlan of(SearchIntent intent) {
        return new SearchUsePlan(
                intent,
                Optional.empty(),
                Optional.empty()
        );
    }

    public boolean usesNotion() {
        return notionReadQuery.isPresent() || notionCreateLocation.isPresent();
    }
}
