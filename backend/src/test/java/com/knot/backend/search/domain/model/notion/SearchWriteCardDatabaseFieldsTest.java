package com.knot.backend.search.domain.model.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchWriteCardDatabaseFieldsTest {
    private static final LocalDate MEETING_DATE = LocalDate.parse("2026-11-13");

    @Test
    @DisplayName("제목 속성에는 초안 제목, 날짜 속성이 하나면 회의 날짜를 넣고 나머지는 채우지 않는다")
    void from_titleAndSingleDate() {
        // given
        List<SearchWriteCardDatabaseFields.Property> properties = List.of(
                new SearchWriteCardDatabaseFields.Property(
                        "이름",
                        "title"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "날짜",
                        "date"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "참석자",
                        "people"
                )
        );

        // when
        List<SearchWriteCardDatabaseField> fields = SearchWriteCardDatabaseFields.from(
                properties,
                "주간 회의",
                MEETING_DATE
        );

        // then
        assertThat(fields).containsExactly(
                new SearchWriteCardDatabaseField(
                        "이름",
                        "주간 회의",
                        "title"
                ),
                new SearchWriteCardDatabaseField(
                        "날짜",
                        "2026-11-13",
                        "date"
                )
        );
    }

    @Test
    @DisplayName("날짜 속성이 두 개 이상이면 모두 비워 둔다")
    void from_twoDates() {
        // given
        List<SearchWriteCardDatabaseFields.Property> properties = List.of(
                new SearchWriteCardDatabaseFields.Property(
                        "이름",
                        "title"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "시작",
                        "date"
                ),
                new SearchWriteCardDatabaseFields.Property(
                        "마감",
                        "date"
                )
        );

        // when
        List<SearchWriteCardDatabaseField> fields = SearchWriteCardDatabaseFields.from(
                properties,
                "주간 회의",
                MEETING_DATE
        );

        // then
        assertThat(fields).extracting(SearchWriteCardDatabaseField::name)
                .containsExactly("이름");
    }
}
