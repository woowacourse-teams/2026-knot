package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.SearchAnswerGuidance;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import java.util.List;
import java.util.function.Consumer;

/**
 * 회의 검색 항목마다 답변이 근거를 확인하지 못한 범위를 답글 끝에 밝힌다(SEARCH-R3). 노션을 읽지 못한
 * 사유(SEARCH-R39)와는 따로 둔다. shortcut:
 * SearchStreamingAnswerGenerator.publishCoverage와 같은 문장을 노션 답변용으로 따로 두었다, 열린 탐색
 * PR(#1157, #1158)이 머지되면 하나로 합친다.
 */
final class SearchAnswerCoverage {

    private SearchAnswerCoverage() {}

    static void publish(
            List<Item> items,
            List<Long> citedIds,
            boolean documentsLimited,
            Consumer<String> onDelta
    ) {
        StringBuilder guidance = new StringBuilder();
        for (Item item : items) {
            if (item.documentIds()
                    .stream()
                    .anyMatch(citedIds::contains)) {
                continue;
            }
            guidance.append("\n\n요청의 ")
                    .append(item.ordinal())
                    .append("번째 항목은 ")
                    .append(description(item));
        }
        if (documentsLimited) {
            guidance.append("\n\n")
                    .append(SearchAnswerGuidance.DOCUMENT_BUDGET_LIMITED);
        }
        if (!guidance.isEmpty()) {
            onDelta.accept(guidance.toString());
        }
    }

    private static String description(Item item) {
        return switch (item.status()) {
            case UNAVAILABLE -> "회의 기록을 조회하지 못했어요.";
            case NO_MATCH -> "검색한 범위에서 관련 기록을 찾지 못했어요.";
            case MATCHED, EXCLUDED -> "답변을 뒷받침할 근거를 확인하지 못했어요.";
        };
    }
}
