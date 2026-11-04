package com.knot.backend.search.infrastructure.llm;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchDateFilter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern
            .compile("(?<![0-9])(?:(\\d{4})[-./])?(\\d{1,2})[-./](\\d{1,2})(?![0-9])");
    private static final Pattern RELATIVE = Pattern.compile("(?<![가-힣])(오늘|어제)(?![가-힣])");
    private static final Pattern GENERIC = Pattern.compile("(?<![가-힣])(회의|요약|정리)(?![가-힣])");

    private final LocalDate date;
    private final String query;

    private SearchDateFilter(
            LocalDate date,
            String query
    ) {
        this.date = date;
        this.query = query;
    }

    public static SearchDateFilter parse(
            String question,
            Instant reference
    ) {
        LocalDate today = reference.atZone(ZONE)
                .toLocalDate();
        Matcher relative = RELATIVE.matcher(question);
        Matcher absolute = DATE.matcher(question);
        if (isRangeOrMultipleDates(
                question,
                relative,
                absolute
        )) {
            return null;
        }
        relative.reset();
        absolute.reset();
        if (relative.find()) {
            return new SearchDateFilter(
                    relativeDate(
                            today,
                            relative.group(1)
                    ),
                    clean(relative.replaceFirst(" "))
            );
        }
        if (!absolute.find()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(
                    year(
                            today,
                            absolute.group(1)
                    ),
                    Integer.parseInt(absolute.group(2)),
                    Integer.parseInt(absolute.group(3))
            );
            return new SearchDateFilter(
                    date,
                    clean(absolute.replaceFirst(" "))
            );
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static LocalDate relativeDate(
            LocalDate today,
            String expression
    ) {
        if (expression.equals("어제")) {
            return today.minusDays(1);
        }
        return today;
    }

    private static int year(
            LocalDate today,
            String year
    ) {
        if (year == null) {
            return today.getYear();
        }
        return Integer.parseInt(year);
    }

    private static boolean isRangeOrMultipleDates(
            String question,
            Matcher relative,
            Matcher absolute
    ) {
        if (question.contains("부터") || question.contains("까지") || question.contains("~")) {
            return true;
        }
        int count = 0;
        while (relative.find()) {
            count++;
        }
        while (absolute.find()) {
            count++;
        }
        return count > 1;
    }

    private static String clean(String text) {
        return GENERIC.matcher(text)
                .replaceAll(" ")
                .strip();
    }

    public String query() {
        return query;
    }

    public Instant from() {
        return date.atStartOfDay(ZONE)
                .toInstant();
    }

    public Instant before() {
        return date.plusDays(1)
                .atStartOfDay(ZONE)
                .toInstant();
    }
}
