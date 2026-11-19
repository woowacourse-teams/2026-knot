package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

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
    @DisplayName("상대 날짜 범위는 끝날을 포함하고 연결할 수 없는 여러 날짜는 되묻는다")
    void parse_success_resolvesRelativeRangeAndClarifiesDisconnectedDates() {
        Instant reference = Instant.parse("2026-10-28T00:00:00Z");

        SearchDateFilter filter = SearchDateFilter.parse(
                "어제부터 오늘까지 회의",
                reference
        );
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
        assertThat(filter.before()).isEqualTo(Instant.parse("2026-10-28T15:00:00Z"));
        assertThat(
                SearchDateFilter.parse(
                        "10/27과 10/28 회의",
                        reference
                )
                        .requiresClarification()
        ).isTrue();
        assertThat(
                SearchDateFilter.parse(
                        "2/30 회의",
                        reference
                )
                        .requiresClarification()
        ).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "일요일의 지난주|2026-11-15T23:59:59+09:00|지난주 서울에서 열린 회의|2026-11-02T00:00+09:00|2026-11-09T00:00+09:00",
            "월요일의 지난주|2026-11-16T00:00:00+09:00|지난주 서울에서 열린 회의|2026-11-09T00:00+09:00|2026-11-16T00:00+09:00",
            "연말 범위|2027-01-04T10:00+09:00|12월 30일부터 1월 2일까지 열린 회의|2026-12-30T00:00+09:00|2027-01-03T00:00+09:00",
            "연도 없는 과거|2027-01-04T10:00+09:00|12월 30일에 열린 회의|2026-12-30T00:00+09:00|2026-12-31T00:00+09:00",
            "명시된 연도|2027-01-04T10:00+09:00|2025년 12월 30일에 열린 회의|2025-12-30T00:00+09:00|2025-12-31T00:00+09:00",
            "월이 생략된 끝날|2026-11-17T10:00+09:00|11월 10일부터 15일까지 열린 회의|2026-11-10T00:00+09:00|2026-11-16T00:00+09:00",
            "기간의 끝날|2026-11-17T10:00+09:00|11월 10일부터 11월 15일까지 열린 회의|2026-11-10T00:00+09:00|2026-11-16T00:00+09:00",
            "날짜 이전|2026-11-17T10:00+09:00|11월 15일 이전에 열린 회의||2026-11-15T00:00+09:00",
            "날짜 이후|2026-11-17T10:00+09:00|11월 15일 이후에 열린 회의|2026-11-16T00:00+09:00|",
            "정각부터|2026-11-17T10:00+09:00|11월 15일 오후 3시부터 시작한 회의|2026-11-15T15:00+09:00|2026-11-16T00:00+09:00",
            "정각까지|2026-11-17T10:00+09:00|11월 15일 오후 3시까지 시작한 회의|2026-11-15T00:00+09:00|2026-11-15T15:00:00.000001+09:00",
            "정각 이전|2026-11-17T10:00+09:00|11월 15일 오후 3시 이전에 시작한 회의|2026-11-15T00:00+09:00|2026-11-15T15:00+09:00",
            "정각 미만|2026-11-17T10:00+09:00|11월 15일 오후 3시 미만에 시작한 회의|2026-11-15T00:00+09:00|2026-11-15T15:00+09:00",
            "정각 이후|2026-11-17T10:00+09:00|11월 15일 오후 3시 이후에 시작한 회의|2026-11-15T15:00:00.000001+09:00|2026-11-16T00:00+09:00"}, delimiter = '|')
    void parse_success_resolvesKstBoundaries(
            String scenario,
            String reference,
            String question,
            String from,
            String before
    ) {
        SearchDateFilter filter = SearchDateFilter.parseMeetingDate(
                question,
                instant(reference)
        );

        assertThat(filter).as(scenario)
                .isNotNull();
        assertThat(filter.from()).isEqualTo(instant(from));
        assertThat(filter.before()).isEqualTo(instant(before));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026년 2월 29일에 열린 회의", "11월 15일 회의 중 11월 14일 이전에 시작한 회의", "오후 3시 이전에 시작한 회의",
            "11월 15일에 끝난 회의", "11월 15일 오후 15시 이전에 시작한 회의"})
    void parse_success_requiresClarificationForUnknownMeetingScope(String question) {
        SearchDateFilter date = SearchDateFilter.parseMeetingDate(
                question,
                instant("2026-11-17T10:00+09:00")
        );

        assertThat(date).isNotNull();
        assertThat(date.requiresClarification()).isTrue();
    }

    @Test
    void parse_success_keepsDiscussionDateAsSearchText() {
        Instant reference = instant("2026-11-17T10:00+09:00");
        assertThat(
                SearchDateFilter.parseMeetingDate(
                        "11월 15일 배포 예정일을 논의한 회의",
                        reference
                )
        ).isNull();
        assertThat(
                SearchDateFilter.parseMeetingDate(
                        "어제 회의에서 논의한 종료된 프로젝트",
                        reference
                )
                        .requiresClarification()
        ).isFalse();

        SearchDateFilter date = SearchDateFilter.parseMeetingDate(
                "11월 12일에 열린 회의에서 11월 15일 배포 일정",
                reference
        );
        assertThat(date.from()).isEqualTo(instant("2026-11-12T00:00+09:00"));
        assertThat(date.query()).contains("11월 15일 배포");
    }

    @Test
    @DisplayName("저장된 대화의 연도보다 명시한 질문의 연도를 우선한다")
    void parse_success_resolvesYearFromContextOrExplicitDate() {
        Instant reference = instant("2027-01-04T10:00+09:00");
        LocalDate context = LocalDate.of(
                2025,
                12,
                30
        );
        SearchDateFilter inherited = SearchDateFilter.parseMeetingDate(
                "12월 31일에 열린 회의",
                reference,
                context
        );
        SearchDateFilter explicit = SearchDateFilter.parseMeetingDate(
                "2026년 12월 31일에 열린 회의",
                reference,
                context
        );

        assertThat(inherited.from()).isEqualTo(instant("2025-12-31T00:00+09:00"));
        assertThat(explicit.from()).isEqualTo(instant("2026-12-31T00:00+09:00"));
    }

    private Instant instant(String value) {
        if (value == null) {
            return null;
        }
        return OffsetDateTime.parse(value)
                .toInstant();
    }
}
