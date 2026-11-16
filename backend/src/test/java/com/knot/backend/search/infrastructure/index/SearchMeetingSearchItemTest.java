package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchMeetingSearchItemTest {
    private static final Instant REFERENCE = Instant.parse("2026-11-14T15:00:00Z");

    @Test
    @DisplayName("문서 1,000개와 배포 날짜를 문서 식별자나 회의 날짜로 사용하지 않는다")
    void parse_success_keepsCountsAndEventDatesAsText() {
        SearchMeetingSearchItem item = SearchMeetingSearchItem.parse(
                "문서 1,000개를 11/15에 배포하기로 했어?",
                REFERENCE
        )
                .getFirst();

        assertThat(item.documentId()).isNull();
        assertThat(item.meetingDate()).isNull();
    }

    @Test
    @DisplayName("문서 번호와 KST 회의 날짜를 각 목록 항목 안에서만 해석한다")
    void parse_success_keepsDatesAndDocumentIdsScopedToItems() {
        List<SearchMeetingSearchItem> items = SearchMeetingSearchItem.parse(
                "1. 문서 #277의 결정을 알려줘\n2. 오늘 열린 회의의 조건을 알려줘",
                REFERENCE
        );

        assertThat(
                items.getFirst()
                        .documentId()
        ).isEqualTo(277L);
        assertThat(
                items.getFirst()
                        .meetingDate()
        ).isNull();
        assertThat(
                items.getLast()
                        .documentId()
        ).isNull();
        assertThat(
                items.getLast()
                        .meetingDate()
                        .from()
        ).isEqualTo(REFERENCE);
    }
}
