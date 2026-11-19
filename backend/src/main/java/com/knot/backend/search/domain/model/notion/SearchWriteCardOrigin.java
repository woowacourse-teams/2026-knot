package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;

/**
 * 카드가 붙은 답변과 그 질문을 한 Member.
 */
public record SearchWriteCardOrigin(
        long answerMessageId,
        long conversationId,
        long workspaceId,
        long memberId
) {

    public SearchWriteCardOrigin {
        if (answerMessageId <= 0 || conversationId <= 0 || workspaceId <= 0 || memberId <= 0) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }
}
