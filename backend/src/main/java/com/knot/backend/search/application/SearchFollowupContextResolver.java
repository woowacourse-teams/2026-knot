package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.query.SearchQuestionContext;
import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import com.knot.backend.search.infrastructure.llm.SearchQueryNormalizer;
import com.knot.backend.search.infrastructure.persistence.SearchQuestionContextStore;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchFollowupContextResolver {
    private static final Pattern SHORT_REFERENCE = Pattern.compile("(누가|왜)[?!]*");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SearchQuestionContextStore contexts;
    private final SearchToolAccess access;
    private final SearchQueryNormalizer normalizer = new SearchQueryNormalizer();

    public boolean isShortReference(String question) {
        return SHORT_REFERENCE.matcher(
                Normalizer.normalize(
                        question.strip(),
                        Normalizer.Form.NFKC
                )
        )
                .matches();
    }

    public Optional<SearchQuestionContext> previousEvidenceContext(SearchToolScope scope) {
        access.validate(scope);
        return contexts.findPreviousEvidenceContext(scope);
    }

    public LocalDate previousMeetingDate(SearchToolScope scope) {
        return previousEvidenceContext(scope).map(this::singleCalendarDay)
                .orElse(null);
    }

    private LocalDate singleCalendarDay(SearchQuestionContext context) {
        if (context.recordedFrom() == null || context.recordedBefore() == null) {
            return null;
        }
        LocalDate start = context.recordedFrom()
                .atZone(SEOUL)
                .toLocalDate();
        LocalDate end = context.recordedBefore()
                .minusNanos(1)
                .atZone(SEOUL)
                .toLocalDate();
        if (start.equals(end)) {
            return start;
        }
        return null;
    }

    public List<SearchDateFilter> dates(
            String question,
            SearchToolScope scope
    ) {
        return SearchDateFilter.splitItems(question)
                .stream()
                .map(
                        item -> SearchDateFilter.parseMeetingDate(
                                item,
                                scope.questionAcceptedAt(),
                                scope.dateContext()
                        )
                )
                .filter(Objects::nonNull)
                .toList();
    }

    public SearchQuestionContext describe(
            String question,
            SearchToolScope scope
    ) {
        List<String> items = SearchDateFilter.splitItems(question);
        if (items.size() == 1) {
            SearchDateFilter date = SearchDateFilter.parseMeetingDate(
                    items.getFirst(),
                    scope.questionAcceptedAt(),
                    scope.dateContext()
            );
            if (date != null && !date.requiresClarification()) {
                return new SearchQuestionContext(
                        normalizer.normalize(date.query()),
                        date.from(),
                        date.before(),
                        null
                );
            }
        }
        return new SearchQuestionContext(
                normalizer.normalize(question),
                null,
                null,
                null
        );
    }
}
