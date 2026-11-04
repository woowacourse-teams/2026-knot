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
        SearchDateFilter date = SearchDateFilter.parse(
                question,
                scope.startedAt()
        );
        String queryText = question;
        if (date != null) {
            queryText = date.query();
        }
        String normalizedQuery = normalizer.normalize(queryText);
        if (normalizedQuery.isBlank() && date == null) {
            return new SearchRetrievalResult(
                    List.of(),
                    0
            );
        }
        SearchDocumentParameters parameters = parameters(
                normalizedQuery,
                date
        );
        List<SearchDocumentRecord> records = findRecords(
                scope,
                normalizedQuery,
                parameters
        );
        return new SearchRetrievalResult(
                documents(records),
                0
        );
    }

    private SearchDocumentParameters parameters(
            String query,
            SearchDateFilter date
    ) {
        if (date == null) {
            return new SearchDocumentParameters(
                    query,
                    null,
                    null,
                    null,
                    null,
                    CANDIDATE_LIMIT
            );
        }
        String search = query;
        if (search.isBlank()) {
            search = null;
        }
        return new SearchDocumentParameters(
                search,
                null,
                date.from(),
                date.before(),
                null,
                CANDIDATE_LIMIT
        );
    }

    private List<SearchDocumentRecord> findRecords(
            SearchToolScope scope,
            String queryText,
            SearchDocumentParameters parameters
    ) {
        if (queryText.isBlank()) {
            return query.listPage(
                    scope,
                    parameters,
                    null
            );
        }
        return query.searchPage(
                scope,
                parameters,
                null
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
                record.content(),
                record.recordingStartedAt(),
                record.createdAt()
        );
    }
}
