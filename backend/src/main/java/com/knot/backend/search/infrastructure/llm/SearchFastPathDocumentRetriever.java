package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentRecord;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SearchFastPathDocumentRetriever implements SearchDocumentRetriever {
    private static final int CANDIDATE_LIMIT = 20;

    private final SearchDocumentQuery query;
    private final SearchToolAccess access;
    private final SearchQueryNormalizer normalizer;

    @Override
    public SearchRetrievalResult retrieve(
            SearchToolScope scope,
            String question
    ) {
        access.validate(scope);
        String normalizedQuery = normalizer.normalize(question);
        if (normalizedQuery.isBlank()) {
            return new SearchRetrievalResult(
                    List.of(),
                    0
            );
        }
        SearchDocumentParameters parameters = new SearchDocumentParameters(
                normalizedQuery,
                null,
                null,
                null,
                null,
                CANDIDATE_LIMIT
        );
        List<SearchDocumentRecord> records = query.searchPage(
                scope,
                parameters,
                null
        )
                .stream()
                .limit(CANDIDATE_LIMIT)
                .toList();
        return new SearchRetrievalResult(
                documents(records),
                0
        );
    }

    private List<SearchDocumentResult> documents(List<SearchDocumentRecord> records) {
        return records.stream()
                .map(this::document)
                .toList();
    }

    private SearchDocumentResult document(SearchDocumentRecord record) {
        return new SearchDocumentResult(
                record.documentId(),
                record.title(),
                record.topic(),
                record.content()
        );
    }
}
