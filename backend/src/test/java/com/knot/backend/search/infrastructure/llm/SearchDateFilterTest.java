package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchDateFilterTest {

    @Test
    @DisplayName("어제를 서울 날짜의 시작부터 다음 날 시작 전까지 조회한다")
    void parse_success_yesterdayInSeoul() {
        // when
        SearchDateFilter filter = SearchDateFilter.parse(
                "어제 회의 요약",
                Instant.parse("2026-10-28T00:10:00Z")
        );
        // then
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
        assertThat(filter.before()).isEqualTo(Instant.parse("2026-10-27T15:00:00Z"));
        assertThat(filter.query()).isEmpty();
    }

    @Test
    @DisplayName("오늘과 명시된 연월일을 서울 날짜로 해석한다")
    void parse_success_todayAndFullDate() {
        // given
        Instant now = Instant.parse("2026-10-27T15:00:00Z");
        // when & then
        assertThat(
                SearchDateFilter.parse(
                        "오늘 회의",
                        now
                )
                        .from()
        ).isEqualTo(now);
        assertThat(
                SearchDateFilter.parse(
                        "2026-10-27 회의",
                        now
                )
                        .from()
        ).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
    }

    @Test
    @DisplayName("월일과 제목 검색어를 분리한다")
    void parse_success_monthDayAndKeyword() {
        // when
        SearchDateFilter filter = SearchDateFilter.parse(
                "10/27 데일리스크럼 요약",
                Instant.parse("2026-10-28T00:00:00Z")
        );
        // then
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
        assertThat(filter.query()).isEqualTo("데일리스크럼");
    }

    @Test
    @DisplayName("여러 날짜나 기간은 한 날짜로 임의 축소하지 않는다")
    void parse_failure_dateRangeIsNotSingleDay() {
        // when & then
        assertThat(
                SearchDateFilter.parse(
                        "어제부터 오늘까지 회의",
                        Instant.parse("2026-10-28T00:00:00Z")
                )
        ).isNull();
        assertThat(
                SearchDateFilter.parse(
                        "10/27과 10/28 회의",
                        Instant.parse("2026-10-28T00:00:00Z")
                )
        ).isNull();
    }

    @Test
    @DisplayName("유효하지 않은 날짜는 날짜 필터로 해석하지 않는다")
    void parse_failure_invalidDate() {
        // when & then
        assertThat(
                SearchDateFilter.parse(
                        "2/30 회의",
                        Instant.parse("2026-10-28T00:00:00Z")
                )
        ).isNull();
    }
}
