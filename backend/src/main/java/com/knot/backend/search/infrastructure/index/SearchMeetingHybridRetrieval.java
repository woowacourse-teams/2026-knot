package com.knot.backend.search.infrastructure.index;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.ItemStatus;
import com.knot.backend.search.infrastructure.llm.SearchEvidenceRelevance;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        List<Optional<List<Long>>> matches = new ArrayList<>();
        for (SearchMeetingSearchItem item : items) {
            matches.add(
                    searchItem(
                            scope,
                            item,
                            itemLimit + 1
                    )
            );
        }
        if (matches.stream()
                .noneMatch(Optional::isPresent)) {
            return Optional.empty();
        }
        int primaryLimit = itemLimit;
        List<Optional<List<Long>>> primary = new ArrayList<>(
                matches.stream()
                        .map(
                                match -> match.map(
                                        ids -> ids.stream()
                                                .limit(primaryLimit)
                                                .toList()
                                )
                        )
                        .toList()
        );
        Set<Long> ranked = orderedIds(primary);
        access.validate(scope);
        Map<Long, SearchDocumentResult> current = new LinkedHashMap<>();
        ranked.stream()
                .limit(TOTAL_LIMIT)
                .forEach(
                        id -> documents.find(
                                scope,
                                id
                        )
                                .map(SearchDocumentResult::from)
                                .ifPresent(
                                        document -> current.put(
                                                id,
                                                document
                                        )
                                )
                );
        int excluded = Math.min(
                ranked.size(),
                TOTAL_LIMIT
        ) - current.size();
        boolean limited = ranked.size() > TOTAL_LIMIT || matches.stream()
                .anyMatch(
                        match -> match.isPresent() && match.orElseThrow()
                                .size() > primaryLimit
                );
        boolean checked = checkAdditionalCandidate(
                scope,
                items,
                matches,
                primary,
                current,
                primaryLimit
        );
        List<SearchDocumentResult> selected = orderedIds(primary).stream()
                .limit(TOTAL_LIMIT)
                .filter(current::containsKey)
                .map(current::get)
                .toList();
        return Optional.of(
                new SearchRetrievalResult(
                        selected,
                        excluded,
                        SearchRetrievalResult.Status.SEARCHED,
                        itemResults(
                                primary,
                                selected
                        ),
                        limited,
                        checked
                )
        );
    }

    private Set<Long> orderedIds(List<Optional<List<Long>>> matches) {
        Set<Long> ranked = new LinkedHashSet<>();
        matches.forEach(match -> match.ifPresent(ranked::addAll));
        return ranked;
    }

    private boolean checkAdditionalCandidate(
            SearchToolScope scope,
            List<SearchMeetingSearchItem> items,
            List<Optional<List<Long>>> matches,
            List<Optional<List<Long>>> primary,
            Map<Long, SearchDocumentResult> current,
            int limit
    ) {
        for (int position = 0; position < matches.size(); position++) {
            List<Long> ids = matches.get(position)
                    .orElse(List.of());
            if (ids.size() <= limit) {
                continue;
            }
            List<Long> primaryIds = primary.get(position)
                    .orElseThrow();
            SearchEvidenceRelevance relevance = new SearchEvidenceRelevance(
                    items.get(position)
                            .question()
            );
            List<SearchDocumentResult> available = primaryIds.stream()
                    .filter(current::containsKey)
                    .map(current::get)
                    .toList();
            if (!relevance.needsCandidate(available)) {
                continue;
            }
            long nextId = ids.get(limit);
            SearchDocumentResult candidate = current.get(nextId);
            if (candidate == null) {
                candidate = readAdditionalCandidate(
                        scope,
                        nextId
                ).orElse(null);
            }
            if (candidate != null && relevance.matches(candidate)) {
                List<Long> replacement = new ArrayList<>(primaryIds);
                replacement.removeLast();
                replacement.addFirst(nextId);
                primary.set(
                        position,
                        Optional.of(replacement)
                );
                current.putIfAbsent(
                        nextId,
                        candidate
                );
            }
            return true;
        }
        return false;
    }

    private Optional<SearchDocumentResult> readAdditionalCandidate(
            SearchToolScope scope,
            long documentId
    ) {
        access.validate(scope);
        try {
            return documents.find(
                    scope,
                    documentId
            )
                    .map(SearchDocumentResult::from);
        } catch (RuntimeException exception) {
            if (ServerErrorLogger.isControlFlow(exception)) {
                throw exception;
            }
            log.warn(
                    "Additional search candidate unavailable workspaceId={} errorType={}",
                    scope.workspaceId(),
                    exception.getClass()
                            .getSimpleName()
            );
            return Optional.empty();
        }
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
