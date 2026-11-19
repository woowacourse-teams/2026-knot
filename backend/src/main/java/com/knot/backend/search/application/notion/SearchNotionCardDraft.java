package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCardDraft;

/**
 * 답변 완료와 함께 저장할 쓰기 확인 카드. notionWorkspaceId에 위치를 찾을 때 쓴 연결의 노션 워크스페이스를 담는다.
 */
public record SearchNotionCardDraft(
        String notionWorkspaceId,
        SearchWriteCardDraft draft
) {
}
