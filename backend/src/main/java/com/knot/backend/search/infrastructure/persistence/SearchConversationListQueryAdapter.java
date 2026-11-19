package com.knot.backend.search.infrastructure.persistence;

import com.knot.backend.search.application.SearchAnswerContentPolicy;
import com.knot.backend.search.application.SearchConversationListQuery;
import com.knot.backend.search.application.dto.result.SearchConversationListItemResult;
import com.knot.backend.search.domain.model.SearchConversationCursor;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchConversationListQueryAdapter implements SearchConversationListQuery {

    private final SearchConversationListJpaRepository conversations;

    @Override
    public List<SearchConversationListItemResult> findPage(
            long workspaceId,
            long memberId,
            int size,
            SearchConversationCursor cursor
    ) {
        return conversations.findPage(
                workspaceId,
                memberId,
                cursor != null,
                cursorTime(cursor),
                cursorId(cursor),
                SearchAnswerContentPolicy.SOURCE_UNAVAILABLE,
                PageRequest.of(
                        0,
                        size + 1
                )
        )
                .stream()
                .map(
                        row -> SearchConversationListItemResult.from(
                                row.id(),
                                row.firstQuestion(),
                                row.lastMessage(),
                                row.createdAt(),
                                row.updatedAt()
                        )
                )
                .toList();
    }

    private Instant cursorTime(SearchConversationCursor cursor) {
        if (cursor == null) {
            return Instant.EPOCH;
        }
        return cursor.getUpdatedAt();
    }

    private long cursorId(SearchConversationCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        return cursor.getConversationId();
    }
}
