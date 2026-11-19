package com.knot.backend.search.domain.model.notion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * DB 아래에 만들 때 채우는 칸(SEARCH-R49). 제목 속성에는 초안 제목, 날짜 형식 속성이 한 개면 회의 날짜를 넣고 나머지
 * 속성은 채우지 않는다.
 */
public final class SearchWriteCardDatabaseFields {
    public static final String TITLE_TYPE = "title";
    public static final String DATE_TYPE = "date";

    private SearchWriteCardDatabaseFields() {}

    public static List<SearchWriteCardDatabaseField> from(
            List<Property> properties,
            String title,
            LocalDate meetingDate
    ) {
        List<SearchWriteCardDatabaseField> fields = new ArrayList<>();
        properties.stream()
                .filter(property -> TITLE_TYPE.equals(property.type()))
                .findFirst()
                .map(
                        property -> new SearchWriteCardDatabaseField(
                                property.name(),
                                title,
                                TITLE_TYPE
                        )
                )
                .ifPresent(fields::add);
        List<Property> dates = properties.stream()
                .filter(property -> DATE_TYPE.equals(property.type()))
                .toList();
        if (dates.size() == 1 && meetingDate != null) {
            fields.add(
                    new SearchWriteCardDatabaseField(
                            dates.getFirst()
                                    .name(),
                            meetingDate.toString(),
                            DATE_TYPE
                    )
            );
        }
        return List.copyOf(fields);
    }

    /**
     * 노션 DB 속성의 이름과 형식(노션이 주는 형식 이름 그대로, 예: title, date, people).
     */
    public record Property(
            String name,
            String type
    ) {
    }
}
