package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 노션을 읽지 못해 회의 문서만으로 답한 사유(SEARCH-R39).
 */
@Getter
@Entity
@Table(name = "search_notion_notices")
public class SearchNotionNotice {

    @Id
    @Column(name = "message_id")
    private Long messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SearchNotionNoticeReason reason;

    protected SearchNotionNotice() {}

    public static SearchNotionNotice create(
            long messageId,
            SearchNotionNoticeReason reason
    ) {
        if (messageId <= 0 || reason == null) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        SearchNotionNotice notice = new SearchNotionNotice();
        notice.messageId = messageId;
        notice.reason = reason;
        return notice;
    }
}
