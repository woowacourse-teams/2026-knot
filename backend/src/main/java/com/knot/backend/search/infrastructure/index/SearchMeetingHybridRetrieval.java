package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        } catch (IOException | IllegalStateException exception) {
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
    ) throws IOException {
        List<SearchMeetingSearchItem> items = SearchMeetingSearchItem.parse(
                question,
                scope.questionAcceptedAt()
        );
        int itemLimit = TOTAL_LIMIT;
        if (items.size() > 1) {
            itemLimit = PER_ITEM_LIMIT;
        }
        Set<Long> ranked = new LinkedHashSet<>();
        for (SearchMeetingSearchItem item : items) {
            Optional<List<Long>> itemMatches = index.findCurrent(
                    scope.workspaceId(),
                    item.question(),
                    source.eligibleDocuments(
                            scope,
                            item
                    ),
                    itemLimit
            );
            if (itemMatches.isEmpty()) {
                return Optional.empty();
            }
            ranked.addAll(itemMatches.orElseThrow());
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
                        ) - current.size()
                )
        );
    }
}
