package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import java.util.List;

/**
 * 답변에 붙은 노션 근거, 노션을 읽지 못한 사유, 쓰기 확인 카드. 사유와 카드는 없으면 null이다.
 */
public record SearchAnswerNotionResult(
        List<SearchNotionEvidenceItemResult> evidences,
        SearchNotionNoticeReason notice,
        SearchWriteCardResult writeCard
) {
    public static final SearchAnswerNotionResult EMPTY = new SearchAnswerNotionResult(
            List.of(),
            null,
            null
    );

    public SearchAnswerNotionResult {
        evidences = List.copyOf(evidences);
    }
}
