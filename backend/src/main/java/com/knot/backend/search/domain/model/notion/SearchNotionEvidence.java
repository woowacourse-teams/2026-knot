package com.knot.backend.search.domain.model.notion;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 답변에 실제로 쓴 노션 페이지. 제목과 주소만 저장하고 본문은 저장하지 않는다(SEARCH-R37).
 */
@Getter
@Entity
@Table(name = "search_notion_evidences")
public class SearchNotionEvidence {

    @EmbeddedId
    private SearchNotionEvidenceId id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String url;

    protected SearchNotionEvidence() {}

    public static SearchNotionEvidence create(
            long messageId,
            int rank,
            String title,
            String url
    ) {
        if (messageId <= 0 || rank < 1 || title == null || url == null || url.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        SearchNotionEvidence evidence = new SearchNotionEvidence();
        evidence.id = new SearchNotionEvidenceId(
                messageId,
                rank
        );
        evidence.title = title;
        evidence.url = url;
        return evidence;
    }
}
