package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentRecord;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.ItemStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<String> items = SearchDateFilter.splitItems(question);
        List<SearchDateFilter> dates = items.stream()
                .map(
                        item -> SearchDateFilter.parseMeetingDate(
                                item,
                                scope.questionAcceptedAt(),
                                scope.dateContext()
                        )
                )
                .toList();
        if (dates.stream()
                .anyMatch(date -> date != null && date.requiresClarification())) {
            return SearchRetrievalResult.skipped();
        }
        if (items.size() > 1 && dates.stream()
                .anyMatch(date -> date != null)) {
            return retrieveDatedItems(
                    scope,
                    items,
                    dates
            );
        }
        SearchDateFilter date = null;
        if (items.size() == 1) {
            date = dates.getFirst();
        }
        return retrieveItem(
                scope,
                question,
                date,
                CANDIDATE_LIMIT
        );
    }

    private SearchRetrievalResult retrieveDatedItems(
            SearchToolScope scope,
            List<String> items,
            List<SearchDateFilter> dates
    ) {
        Map<Long, SearchDocumentResult> documents = new LinkedHashMap<>();
        List<Item> coverage = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            SearchRetrievalResult found = retrieveItem(
                    scope,
                    items.get(index),
                    dates.get(index),
                    3
            );
            List<Long> ids = new ArrayList<>();
            for (SearchDocumentResult document : found.documents()) {
                if (documents.containsKey(document.documentId()) || documents.size() < CANDIDATE_LIMIT) {
                    documents.putIfAbsent(
                            document.documentId(),
                            document
                    );
                    ids.add(document.documentId());
                }
            }
            ItemStatus status = ItemStatus.MATCHED;
            if (ids.isEmpty()) {
                status = ItemStatus.NO_MATCH;
                if (!found.documents()
                        .isEmpty()) {
                    status = ItemStatus.EXCLUDED;
                }
            }
            coverage.add(
                    new Item(
                            index + 1,
                            status,
                            ids
                    )
            );
        }
        return new SearchRetrievalResult(
                List.copyOf(documents.values()),
                0,
                SearchRetrievalResult.Status.SEARCHED,
                coverage
        );
    }

    private SearchRetrievalResult retrieveItem(
            SearchToolScope scope,
            String question,
            SearchDateFilter date,
            int limit
    ) {
        String queryText = question;
        if (date != null) {
            queryText = date.query();
        }
        if (scope.recordingSessionId() != null && date == null) {
            queryText = "";
        }
        String normalizedQuery = normalizer.normalize(queryText);
        if (normalizedQuery.isBlank() && date == null && scope.recordingSessionId() == null) {
            return SearchRetrievalResult.skipped();
        }
        SearchDocumentParameters parameters = parameters(
                normalizedQuery,
                date,
                limit
        );
        return new SearchRetrievalResult(
                documents(
                        findRecords(
                                scope,
                                normalizedQuery,
                                parameters
                        )
                ),
                0
        );
    }

    private SearchDocumentParameters parameters(
            String query,
            SearchDateFilter date,
            int limit
    ) {
        String search = query;
        if (search.isBlank()) {
            search = null;
        }
        return new SearchDocumentParameters(
                search,
                null,
                lowerBound(date),
                upperBound(date),
                null,
                limit
        );
    }

    private Instant lowerBound(SearchDateFilter date) {
        if (date == null) {
            return null;
        }
        return date.from();
    }

    private Instant upperBound(SearchDateFilter date) {
        if (date == null) {
            return null;
        }
        return date.before();
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
                .map(SearchDocumentResult::from)
                .toList();
    }
}
