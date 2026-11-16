package com.knot.backend.search.application.dto.result;

import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import java.util.List;

public record SearchRetrievalResult(
        List<SearchDocumentResult> documents,
        int excludedCount,
        Status status,
        List<Item> items
) {
    public SearchRetrievalResult(
            List<SearchDocumentResult> documents,
            int excludedCount
    ) {
        this(
                documents,
                excludedCount,
                Status.SEARCHED,
                List.of()
        );
    }

    public SearchRetrievalResult {
        documents = List.copyOf(documents);
        items = List.copyOf(items);
        if (excludedCount < 0) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
    }

    public static SearchRetrievalResult skipped() {
        return new SearchRetrievalResult(
                List.of(),
                0,
                Status.SKIPPED,
                List.of()
        );
    }

    public static SearchRetrievalResult unavailable() {
        return new SearchRetrievalResult(
                List.of(),
                0,
                Status.UNAVAILABLE,
                List.of()
        );
    }

    public boolean foundNothing() {
        return status == Status.SEARCHED && documents.isEmpty() && excludedCount == 0 && items.isEmpty();
    }

    public enum Status {
        SEARCHED,
        SKIPPED,
        UNAVAILABLE
    }

    public record Item(
            int ordinal,
            ItemStatus status,
            List<Long> documentIds
    ) {
        public Item {
            documentIds = List.copyOf(documentIds);
        }
    }

    public enum ItemStatus {
        MATCHED,
        NO_MATCH,
        UNAVAILABLE,
        EXCLUDED
    }
}
