package com.knot.backend.search.application.notion;

import java.util.List;
import java.util.Optional;

/**
 * 노션 자료를 함께 넘긴 답변 생성 결과. notionEvidences는 답변 AI가 실제로 쓴 노션 페이지를 낸 순서대로 담는다.
 * draft와 issue는 노션 페이지 만들기 초안을 받았을 때만 둘 가운데 하나가 있다.
 */
public record SearchNotionAnswer(
        List<Long> documentIds,
        List<SearchNotionPage> notionEvidences,
        Optional<Draft> draft,
        Optional<SearchNotionWriteSkip> issue
) {

    public SearchNotionAnswer {
        documentIds = List.copyOf(documentIds);
        notionEvidences = List.copyOf(notionEvidences);
    }

    public static SearchNotionAnswer documents(List<Long> documentIds) {
        return new SearchNotionAnswer(
                documentIds,
                List.of(),
                Optional.empty(),
                Optional.empty()
        );
    }

    public record Draft(
            String title,
            String body,
            long documentId
    ) {
    }
}
