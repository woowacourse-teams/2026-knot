package com.knot.backend.search.infrastructure.llm;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchDateFilter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern ITEM = Pattern.compile("(?m)^\\h*[1-9]\\d?[.)]\\h+");
    private static final Pattern DATE = Pattern.compile(
            "(?<![\\p{L}\\d])(?:(?<ky>\\d{4})년\\s*)?(?<km>\\d{1,2})월\\s*(?<kd>\\d{1,2})일"
                    + "|(?<![\\d./-])(?:(?<y>\\d{4})[-./])?(?<m>\\d{1,2})[-./](?<d>\\d{1,2})(?![\\d./-])"
                    + "|(?<![가-힣])(?<relative>오늘|어제|지난\\s*주|이번\\s*주)(?=$|[^가-힣]|부터|까지|이전|이후|미만|에(?![가-힣]))"
                    + "|(?<![\\p{L}\\d])(?<endDay>\\d{1,2})일(?=\\s*까지)"
    );
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\p{L}\\d])(?:(?<meridiem>오전|오후)\\s*)?(?<hour>\\d{1,2})"
                    + "(?:시(?:\\s*(?<minute>\\d{1,2})분)?|:(?<colonMinute>\\d{2}))\\s*" + "(?<bound>부터|까지|이전|이후|미만)"
    );
    private static final Pattern SCHEDULE = Pattern.compile("^\\s*(?:에|에는|로|으로)?\\s*(?:배포|출시|마감|납기|오픈|런칭|제출|완료|예정|일정)");
    private static final Pattern DATE_BOUND = Pattern.compile("^\\s*(이전|이후|미만|까지|부터)");
    private static final Pattern RANGE_LINK = Pattern.compile("\\s*(?:부터|~|～|–|—)\\s*");
    private static final Pattern RANGE_END = Pattern.compile("^\\s*까지");
    private static final Pattern ENDED_OR_OVERLAP = Pattern.compile("(?:종료(?:된|한)|끝난|끝나는|걸친|겹친)\\s*회의");
    private static final Pattern GENERIC = Pattern
            .compile("(?<![가-힣])(?:서울에서|열린|열리는|진행한|했던|시작한|시작하는|시작된|회의(?:만)?|요약|정리)(?![가-힣])");
    private static final SearchDateFilter CLARIFY = new SearchDateFilter(
            null,
            null,
            ""
    );
    private static final SearchDateFilter NEEDS_DATE = new SearchDateFilter(
            null,
            null,
            ""
    );

    private final Instant from;
    private final Instant before;
    private final String query;

    private SearchDateFilter(
            Instant from,
            Instant before,
            String query
    ) {
        this.from = from;
        this.before = before;
        this.query = query;
    }

    public static List<String> splitItems(String question) {
        String stripped = question.strip();
        if (!ITEM.matcher(stripped)
                .lookingAt()) {
            return List.of(stripped);
        }
        return Arrays.stream(ITEM.split(stripped))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public static boolean hasYearlessCalendarDate(String question) {
        return DATE.matcher(question)
                .results()
                .anyMatch(
                        date -> (date.group("km") != null || date.group("m") != null) && year(date) == null
                                && !isSchedule(
                                        question,
                                        date.end()
                                )
                );
    }

    public static SearchDateFilter parseMeetingDate(
            String question,
            Instant reference
    ) {
        return parseMeetingDate(
                question,
                reference,
                null
        );
    }

    public static SearchDateFilter parseMeetingDate(
            String question,
            Instant reference,
            LocalDate contextDate
    ) {
        List<MatchResult> dates = DATE.matcher(question)
                .results()
                .toList();
        List<MatchResult> times = TIME.matcher(question)
                .results()
                .filter(
                        match -> !isSchedule(
                                question,
                                match.end()
                        )
                )
                .toList();
        boolean range = isDateRange(
                question,
                dates
        );
        if (!range) {
            dates = dates.stream()
                    .filter(
                            match -> !isSchedule(
                                    question,
                                    match.end()
                            )
                    )
                    .toList();
        }
        if (dates.isEmpty() && times.isEmpty()) {
            return null;
        }
        if (ENDED_OR_OVERLAP.matcher(question)
                .find() || dates.size() > 1 && !range) {
            return CLARIFY;
        }
        LocalDate today = reference.atZone(ZONE)
                .toLocalDate();
        try {
            return resolve(
                    question,
                    today,
                    contextDate,
                    dates,
                    times,
                    range
            );
        } catch (DateTimeException exception) {
            return CLARIFY;
        }
    }

    public static SearchDateFilter parse(
            String question,
            Instant reference
    ) {
        return parseMeetingDate(
                question,
                reference
        );
    }

    private static SearchDateFilter resolve(
            String question,
            LocalDate today,
            LocalDate contextDate,
            List<MatchResult> dates,
            List<MatchResult> times,
            boolean range
    ) {
        StringBuilder searchText = new StringBuilder(question);
        if (range) {
            if (!times.isEmpty()) {
                return CLARIFY;
            }
            return resolveRange(
                    question,
                    today,
                    contextDate,
                    dates,
                    searchText
            );
        }
        if (dates.isEmpty()) {
            if (contextDate == null) {
                return NEEDS_DATE;
            }
            return resolveTimes(
                    contextDate,
                    times,
                    searchText
            );
        }
        MatchResult date = dates.getFirst();
        if (date.group("endDay") != null) {
            return CLARIFY;
        }
        LocalDate day = day(
                date,
                today,
                contextDate
        );
        String relative = date.group("relative");
        if (relative != null && relative.endsWith("주")) {
            if (!times.isEmpty()) {
                return CLARIFY;
            }
            erase(
                    searchText,
                    date.start(),
                    date.end()
            );
            return filter(
                    start(day),
                    start(day.plusWeeks(1)),
                    searchText
            );
        }
        Matcher bound = DATE_BOUND.matcher(question.substring(date.end()));
        if (bound.find()) {
            if (!times.isEmpty()) {
                return CLARIFY;
            }
            erase(
                    searchText,
                    date.start(),
                    date.end() + bound.end()
            );
            return switch (bound.group(1)) {
                case "이후" -> filter(
                        start(day.plusDays(1)),
                        null,
                        searchText
                );
                case "부터" -> filter(
                        start(day),
                        null,
                        searchText
                );
                case "까지" -> filter(
                        null,
                        start(day.plusDays(1)),
                        searchText
                );
                default -> filter(
                        null,
                        start(day),
                        searchText
                );
            };
        }
        erase(
                searchText,
                date.start(),
                date.end()
        );
        return resolveTimes(
                day,
                times,
                searchText
        );
    }

    private static boolean isDateRange(
            String question,
            List<MatchResult> dates
    ) {
        if (dates.size() != 2) {
            return false;
        }
        String link = question.substring(
                dates.getFirst()
                        .end(),
                dates.getLast()
                        .start()
        )
                .strip();
        return RANGE_LINK.matcher(link)
                .matches()
                && (!"부터".equals(link) || RANGE_END.matcher(
                        question.substring(
                                dates.getLast()
                                        .end()
                        )
                )
                        .find())
                && !isSchedule(
                        question,
                        dates.getLast()
                                .end()
                );
    }

    private static SearchDateFilter resolveRange(
            String question,
            LocalDate today,
            LocalDate contextDate,
            List<MatchResult> dates,
            StringBuilder searchText
    ) {
        MatchResult first = dates.getFirst();
        MatchResult last = dates.getLast();
        LocalDate start = day(
                first,
                today,
                contextDate
        );
        if (year(first) == null && year(last) != null && first.group("relative") == null) {
            LocalDate specifiedEnd = calendarDay(
                    last,
                    today.getYear()
            );
            start = calendarDay(
                    first,
                    specifiedEnd.getYear()
            );
            if (start.isAfter(specifiedEnd)) {
                start = calendarDay(
                        first,
                        specifiedEnd.getYear() - 1
                );
            }
        }
        LocalDate end = rangeEnd(
                last,
                start,
                today
        );
        Matcher suffix = RANGE_END.matcher(question.substring(last.end()));
        int finish = last.end();
        if (suffix.find()) {
            finish += suffix.end();
        }
        erase(
                searchText,
                first.start(),
                finish
        );
        return filter(
                start(start),
                start(end),
                searchText
        );
    }

    private static LocalDate rangeEnd(
            MatchResult last,
            LocalDate start,
            LocalDate today
    ) {
        String relative = last.group("relative");
        if (relative != null) {
            LocalDate end = day(
                    last,
                    today,
                    null
            );
            if (relative.endsWith("주")) {
                return end.plusWeeks(1);
            }
            return end.plusDays(1);
        }
        if (last.group("endDay") != null) {
            int endDay = Integer.parseInt(last.group("endDay"));
            LocalDate month = start;
            if (endDay < start.getDayOfMonth()) {
                month = month.plusMonths(1);
            }
            return month.withDayOfMonth(endDay)
                    .plusDays(1);
        }
        LocalDate end = calendarDay(
                last,
                start.getYear()
        );
        if (year(last) == null && end.isBefore(start)) {
            end = calendarDay(
                    last,
                    start.getYear() + 1
            );
        }
        return end.plusDays(1);
    }

    private static SearchDateFilter resolveTimes(
            LocalDate day,
            List<MatchResult> times,
            StringBuilder searchText
    ) {
        Instant from = start(day);
        Instant before = start(day.plusDays(1));
        for (MatchResult time : times) {
            Instant instant = day.atTime(clockTime(time))
                    .atZone(ZONE)
                    .toInstant();
            switch (time.group("bound")) {
                case "부터" -> from = later(
                        from,
                        instant
                );
                case "이후" -> from = later(
                        from,
                        instant.plus(
                                1,
                                ChronoUnit.MICROS
                        )
                );
                case "까지" -> before = earlier(
                        before,
                        instant.plus(
                                1,
                                ChronoUnit.MICROS
                        )
                );
                default -> before = earlier(
                        before,
                        instant
                );
            }
            erase(
                    searchText,
                    time.start(),
                    time.end()
            );
        }
        return filter(
                from,
                before,
                searchText
        );
    }

    private static LocalTime clockTime(MatchResult time) {
        int hour = Integer.parseInt(time.group("hour"));
        String meridiem = time.group("meridiem");
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                throw new DateTimeException("Invalid 12-hour clock");
            }
            hour %= 12;
            if ("오후".equals(meridiem)) {
                hour += 12;
            }
        }
        String minute = time.group("minute");
        if (minute == null) {
            minute = time.group("colonMinute");
        }
        if (minute == null) {
            return LocalTime.of(
                    hour,
                    0
            );
        }
        return LocalTime.of(
                hour,
                Integer.parseInt(minute)
        );
    }

    private static LocalDate day(
            MatchResult match,
            LocalDate today,
            LocalDate contextDate
    ) {
        String relative = match.group("relative");
        if (relative != null) {
            return switch (relative.replaceAll(
                    "\\s+",
                    ""
            )) {
                case "어제" -> today.minusDays(1);
                case "지난주" -> today.minusDays(
                        today.getDayOfWeek()
                                .getValue() + 6L
                );
                case "이번주" -> today.minusDays(
                        today.getDayOfWeek()
                                .getValue() - 1L
                );
                default -> today;
            };
        }
        if (contextDate != null) {
            return calendarDay(
                    match,
                    contextDate.getYear()
            );
        }
        LocalDate date = calendarDay(
                match,
                today.getYear()
        );
        if (year(match) == null && date.isAfter(today)) {
            return calendarDay(
                    match,
                    today.getYear() - 1
            );
        }
        return date;
    }

    private static LocalDate calendarDay(
            MatchResult match,
            int defaultYear
    ) {
        String explicitYear = year(match);
        if (explicitYear != null) {
            defaultYear = Integer.parseInt(explicitYear);
        }
        if (match.group("km") != null) {
            return LocalDate.of(
                    defaultYear,
                    Integer.parseInt(match.group("km")),
                    Integer.parseInt(match.group("kd"))
            );
        }
        return LocalDate.of(
                defaultYear,
                Integer.parseInt(match.group("m")),
                Integer.parseInt(match.group("d"))
        );
    }

    private static String year(MatchResult match) {
        String koreanYear = match.group("ky");
        if (koreanYear != null) {
            return koreanYear;
        }
        return match.group("y");
    }

    private static boolean isSchedule(
            String question,
            int end
    ) {
        return SCHEDULE.matcher(question.substring(end))
                .find();
    }

    private static void erase(
            StringBuilder text,
            int start,
            int end
    ) {
        text.replace(
                start,
                end,
                " ".repeat(end - start)
        );
    }

    private static SearchDateFilter filter(
            Instant from,
            Instant before,
            StringBuilder searchText
    ) {
        if (from != null && before != null && !from.isBefore(before)) {
            return CLARIFY;
        }
        String cleaned = GENERIC.matcher(searchText)
                .replaceAll(" ")
                .replaceAll(
                        "(?<![가-힣])(?:에|에는|의)(?![가-힣])",
                        " "
                )
                .strip();
        return new SearchDateFilter(
                from,
                before,
                cleaned
        );
    }

    private static Instant later(
            Instant first,
            Instant second
    ) {
        if (first.isAfter(second)) {
            return first;
        }
        return second;
    }

    private static Instant earlier(
            Instant first,
            Instant second
    ) {
        if (first.isBefore(second)) {
            return first;
        }
        return second;
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(ZONE)
                .toInstant();
    }

    public boolean requiresClarification() {
        return this == CLARIFY || this == NEEDS_DATE;
    }

    public boolean needsDateContext() {
        return this == NEEDS_DATE;
    }

    public String query() {
        return query;
    }

    public Instant from() {
        return from;
    }

    public Instant before() {
        return before;
    }
}
