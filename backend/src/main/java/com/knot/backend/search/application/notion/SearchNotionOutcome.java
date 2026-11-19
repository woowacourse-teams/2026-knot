package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import java.util.List;
import java.util.Optional;

/**
 * 답변 완료와 함께 저장할 노션 안내, 노션 근거, 쓰기 확인 카드.
 */
public record SearchNotionOutcome(
        Optional<SearchNotionNoticeReason> notice,
        List<SearchNotionPage> evidences,
        Optional<SearchNotionCardDraft> card
) {

    public SearchNotionOutcome {
        evidences = List.copyOf(evidences);
    }

    public static SearchNotionOutcome notice(Optional<SearchNotionNoticeReason> notice) {
        return new SearchNotionOutcome(
                notice,
                List.of(),
                Optional.empty()
        );
    }
}
