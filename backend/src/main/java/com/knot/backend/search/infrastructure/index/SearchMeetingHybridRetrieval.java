package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.ItemStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SearchMeetingHybridRetrieval {
    private static final Logger log = LoggerFactory.getLogger(SearchMeetingHybridRetrieval.class);
    private static final int PER_ITEM_LIMIT = 3;
    private static final int TOTAL_LIMIT = 20;

    private final SearchMeetingDocumentIndex index;
    private final SearchMeetingIndexSource source;
    private final SearchDocumentQuery documents;
    private final SearchToolAccess access;
    private final Set<Long> workspaceIds;

    SearchMeetingHybridRetrieval(
            SearchMeetingDocumentIndex index,
            SearchMeetingIndexSource source,
            SearchDocumentQuery documents,
            SearchToolAccess access,
            List<Long> workspaceIds
    ) {
        this.index = index;
        this.source = source;
        this.documents = documents;
        this.access = access;
        this.workspaceIds = Set.copyOf(workspaceIds);
    }

    public Optional<SearchRetrievalResult> retrieve(
            SearchToolScope scope,
            String question
    ) {
        if (!workspaceIds.contains(scope.workspaceId())) {
            return Optional.empty();
        }
        access.validate(scope);
        try {
            return retrieveCurrent(
                    scope,
                    question
            );
        } catch (IllegalStateException exception) {
            log.warn(
                    "Hybrid search unavailable workspaceId={} errorType={}",
                    scope.workspaceId(),
                    exception.getClass()
                            .getSimpleName()
            );
            return Optional.empty();
        }
    }

    private Optional<SearchRetrievalResult> retrieveCurrent(
            SearchToolScope scope,
            String question
    ) {
        List<SearchMeetingSearchItem> items = SearchMeetingSearchItem.parse(
                question,
                scope.questionAcceptedAt(),
                scope.dateContext()
        );
        if (items.stream()
                .map(SearchMeetingSearchItem::meetingDate)
                .anyMatch(date -> date != null && date.requiresClarification())) {
            return Optional.of(SearchRetrievalResult.skipped());
        }
        int itemLimit = TOTAL_LIMIT;
        if (items.size() > 1) {
            itemLimit = PER_ITEM_LIMIT;
        }
        Set<Long> ranked = new LinkedHashSet<>();
        List<Optional<List<Long>>> matches = new ArrayList<>();
        for (SearchMeetingSearchItem item : items) {
            Optional<List<Long>> itemMatches = searchItem(
                    scope,
                    item,
                    itemLimit
            );
            matches.add(itemMatches);
            itemMatches.ifPresent(ranked::addAll);
        }
        if (matches.stream()
                .noneMatch(Optional::isPresent)) {
            return Optional.empty();
        }
        access.validate(scope);
        List<SearchDocumentResult> current = ranked.stream()
                .limit(TOTAL_LIMIT)
                .map(
                        id -> documents.find(
                                scope,
                                id
                        )
                )
                .flatMap(Optional::stream)
                .map(SearchDocumentResult::from)
                .toList();
        return Optional.of(
                new SearchRetrievalResult(
                        current,
                        Math.min(
                                ranked.size(),
                                TOTAL_LIMIT
                        ) - current.size(),
                        SearchRetrievalResult.Status.SEARCHED,
                        itemResults(
                                matches,
                                current
                        )
                )
        );
    }

    private Optional<List<Long>> searchItem(
            SearchToolScope scope,
            SearchMeetingSearchItem item,
            int limit
    ) {
        try {
            return index.findCurrent(
                    scope.workspaceId(),
                    item.question(),
                    source.eligibleDocuments(
                            scope,
                            item
                    ),
                    limit
            );
        } catch (IOException | IllegalStateException exception) {
            log.warn(
                    "Hybrid search item unavailable workspaceId={} errorType={}",
                    scope.workspaceId(),
                    exception.getClass()
                            .getSimpleName()
            );
            return Optional.empty();
        }
    }

    private List<Item> itemResults(
            List<Optional<List<Long>>> matches,
            List<SearchDocumentResult> current
    ) {
        if (matches.size() < 2) {
            return List.of();
        }
        Set<Long> currentIds = current.stream()
                .map(SearchDocumentResult::documentId)
                .collect(Collectors.toSet());
        List<Item> results = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            results.add(
                    itemResult(
                            index + 1,
                            matches.get(index),
                            currentIds
                    )
            );
        }
        return results;
    }

    private Item itemResult(
            int ordinal,
            Optional<List<Long>> match,
            Set<Long> currentIds
    ) {
        if (match.isEmpty()) {
            return new Item(
                    ordinal,
                    ItemStatus.UNAVAILABLE,
                    List.of()
            );
        }
        List<Long> ranked = match.orElseThrow();
        if (ranked.isEmpty()) {
            return new Item(
                    ordinal,
                    ItemStatus.NO_MATCH,
                    List.of()
            );
        }
        List<Long> visible = ranked.stream()
                .filter(currentIds::contains)
                .toList();
        if (visible.isEmpty()) {
            return new Item(
                    ordinal,
                    ItemStatus.EXCLUDED,
                    List.of()
            );
        }
        return new Item(
                ordinal,
                ItemStatus.MATCHED,
                visible
        );
    }
}
