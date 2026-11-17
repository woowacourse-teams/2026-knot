package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record SearchMeetingSearchItem(
        String question,
        Long documentId,
        SearchDateFilter meetingDate
) {
    private static final Pattern DOCUMENT = Pattern
            .compile("(?<![\\p{L}\\d])문서\\s*#?\\s*([1-9][0-9]{0,18}+)(?!\\d|[,.]\\d|\\s*(?:개|건|종|편|쪽|페이지))");

    static List<SearchMeetingSearchItem> parse(
            String question,
            Instant reference
    ) {
        return parse(
                question,
                reference,
                null
        );
    }

    static List<SearchMeetingSearchItem> parse(
            String question,
            Instant reference,
            LocalDate contextDate
    ) {
        return SearchDateFilter.splitItems(question)
                .stream()
                .map(
                        value -> item(
                                value,
                                reference,
                                contextDate
                        )
                )
                .toList();
    }

    private static SearchMeetingSearchItem item(
            String question,
            Instant reference,
            LocalDate contextDate
    ) {
        Long documentId = explicitDocumentId(question);
        return new SearchMeetingSearchItem(
                question,
                documentId,
                SearchDateFilter.parseMeetingDate(
                        question,
                        reference,
                        contextDate
                )
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
