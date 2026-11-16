package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.query.SearchQuestionContext;
import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import com.knot.backend.search.infrastructure.llm.SearchQueryNormalizer;
import com.knot.backend.search.infrastructure.persistence.SearchQuestionContextStore;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchFollowupContextResolver {
    private static final Pattern SHORT_REFERENCE = Pattern.compile("(누가|왜)[?!]*");

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

    public SearchQuestionContext describe(
            String question,
            Instant referenceAt
    ) {
        SearchDateFilter date = SearchDateFilter.parseMeetingDate(
                question,
                referenceAt
        );
        if (date == null) {
            return new SearchQuestionContext(
                    normalizer.normalize(question),
                    null,
                    null,
                    null
            );
        }
        return new SearchQuestionContext(
                normalizer.normalize(date.query()),
                date.from(),
                date.before(),
                null
        );
    }
}
