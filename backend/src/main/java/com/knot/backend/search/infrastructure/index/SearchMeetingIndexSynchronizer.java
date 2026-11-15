package com.knot.backend.search.infrastructure.index;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class SearchMeetingIndexSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(SearchMeetingIndexSynchronizer.class);

    private final SearchMeetingDocumentIndex index;
    private final SearchMeetingIndexSource source;
    private final List<Long> workspaceIds;

    SearchMeetingIndexSynchronizer(
            SearchMeetingDocumentIndex index,
            SearchMeetingIndexSource source,
            List<Long> workspaceIds
    ) {
        if (workspaceIds.isEmpty() || workspaceIds.stream()
                .anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Search index requires explicit positive workspace IDs");
        }
        this.index = index;
        this.source = source;
        this.workspaceIds = List.copyOf(workspaceIds);
    }

    @Scheduled(initialDelayString = "${knot.search.index.initial-delay:PT15S}", fixedDelayString = "${knot.search.index.refresh-delay:PT5M}")
    void refresh() {
        for (long workspaceId : workspaceIds) {
            refresh(workspaceId);
        }
    }

    private void refresh(long workspaceId) {
        try {
            if (!index.reconcile(
                    workspaceId,
                    () -> source.documents(workspaceId)
            )) {
                log.info(
                        "Search index source changed during refresh workspaceId={}",
                        workspaceId
                );
            }
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "Search index refresh failed workspaceId={} errorType={}",
                    workspaceId,
                    exception.getClass()
                            .getSimpleName()
            );
        }
    }
}
