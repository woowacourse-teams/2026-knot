package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import java.util.List;
import java.util.Optional;

/**
 * 노션 읽기 결과. 읽은 페이지가 있으면 notice가 비고, 읽지 못했으면 pages가 빈다(SEARCH-R39).
 */
public record SearchNotionReadResult(
        List<SearchNotionPage> pages,
        Optional<SearchNotionNoticeReason> notice
) {

    public SearchNotionReadResult {
        pages = List.copyOf(pages);
    }

    public static SearchNotionReadResult read(List<SearchNotionPage> pages) {
        return new SearchNotionReadResult(
                pages,
                Optional.empty()
        );
    }

    public static SearchNotionReadResult failed(SearchNotionNoticeReason reason) {
        return new SearchNotionReadResult(
                List.of(),
                Optional.of(reason)
        );
    }
}
