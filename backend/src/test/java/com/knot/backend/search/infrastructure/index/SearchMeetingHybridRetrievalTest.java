package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentRecord;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.ItemStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchMeetingHybridRetrievalTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final String QUESTION = "1. 배포 회의\n2. 홍보 회의";

    private final SearchToolScope scope = new SearchToolScope(
            1,
            1,
            1,
            1,
            2,
            UUID.randomUUID(),
            NOW,
            NOW.plusSeconds(30)
    );
    private final SearchMeetingDocumentIndex index = mock(SearchMeetingDocumentIndex.class);
    private final SearchDocumentQuery documents = mock(SearchDocumentQuery.class);
    private final SearchMeetingHybridRetrieval retrieval = new SearchMeetingHybridRetrieval(
            index,
            mock(SearchMeetingIndexSource.class),
            documents,
            mock(SearchToolAccess.class),
            List.of(1L)
    );

    @Test
    @DisplayName("뒤 항목 검색에 실패해도 앞 항목에서 현재 읽을 수 있는 근거는 보존한다")
    void retrieve_success_keepsAvailableItemAfterOtherItemFails() throws IOException {
        when(
                index.findCurrent(
                        eq(1L),
                        eq("배포 회의"),
                        any(),
                        anyInt()
                )
        ).thenReturn(Optional.of(List.of(7L)));
        when(
                index.findCurrent(
                        eq(1L),
                        eq("홍보 회의"),
                        any(),
                        anyInt()
                )
        ).thenThrow(new IOException("index read failed"));
        when(
                documents.find(
                        scope,
                        7L
                )
        ).thenReturn(
                Optional.of(
                        new SearchDocumentRecord(
                                7,
                                "배포",
                                "일정",
                                "금요일 배포",
                                DocumentStatus.DRAFT,
                                NOW,
                                NOW,
                                null,
                                1
                        )
                )
        );

        SearchRetrievalResult result = retrieval.retrieve(
                scope,
                QUESTION
        )
                .orElseThrow();

        assertThat(result.documents()).extracting(SearchDocumentResult::documentId)
                .containsExactly(7L);
        assertThat(result.items()).containsExactly(
                new Item(
                        1,
                        ItemStatus.MATCHED,
                        List.of(7L)
                ),
                new Item(
                        2,
                        ItemStatus.UNAVAILABLE,
                        List.of()
                )
        );
    }

    @Test
    @DisplayName("어느 항목도 조회할 수 없으면 기존 검색으로 넘긴다")
    void retrieve_success_defersWhenEveryItemIsUnavailable() throws IOException {
        when(
                index.findCurrent(
                        eq(1L),
                        any(),
                        any(),
                        anyInt()
                )
        ).thenReturn(Optional.empty());

        assertThat(
                retrieval.retrieve(
                        scope,
                        QUESTION
                )
        ).isEmpty();
    }
}
