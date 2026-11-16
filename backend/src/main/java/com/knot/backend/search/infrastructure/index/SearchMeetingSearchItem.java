package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record SearchMeetingSearchItem(
        String question,
        Long documentId,
        SearchDateFilter meetingDate
) {
    private static final Pattern ITEM = Pattern.compile("(?m)^\\h*[1-9]\\d?[.)]\\h+");
    private static final Pattern DOCUMENT = Pattern
            .compile("(?<![\\p{L}\\d])문서\\s*#?\\s*([1-9][0-9]{0,18}+)(?!\\d|[,.]\\d|\\s*(?:개|건|종|편|쪽|페이지))");
    private static final Pattern MEETING_DATE = Pattern
            .compile("(?:오늘|어제|(?:\\d{4}[-./])?\\d{1,2}[-./]\\d{1,2})\\s*(?:에\\s*)?" + "(?:(?:열린|진행한|했던)\\s*)?회의");

    static List<SearchMeetingSearchItem> parse(
            String question,
            Instant reference
    ) {
        String stripped = question.strip();
        if (!ITEM.matcher(stripped)
                .lookingAt()) {
            return List.of(
                    item(
                            stripped,
                            reference
                    )
            );
        }
        return Arrays.stream(ITEM.split(stripped))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(
                        value -> item(
                                value,
                                reference
                        )
                )
                .toList();
    }

    private static SearchMeetingSearchItem item(
            String question,
            Instant reference
    ) {
        Long documentId = explicitDocumentId(question);
        if (MEETING_DATE.matcher(question)
                .find()) {
            return new SearchMeetingSearchItem(
                    question,
                    documentId,
                    SearchDateFilter.parse(
                            question,
                            reference
                    )
            );
        }
        return new SearchMeetingSearchItem(
                question,
                documentId,
                null
        );
    }

    private static Long explicitDocumentId(String question) {
        Matcher matcher = DOCUMENT.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        String first = matcher.group(1);
        if (matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
