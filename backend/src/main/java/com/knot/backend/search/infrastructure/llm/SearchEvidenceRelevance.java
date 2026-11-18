package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SearchEvidenceRelevance {
    private static final Pattern WORD = Pattern.compile("[\\p{L}]{2,}");
    private static final Pattern KOREAN_SUFFIX = Pattern
            .compile("(?:에서는|에서|에게|으로|처럼|하고|하기로|하는|했다|했는지|하려고|할|를|을|은|는|이|가|의|와|과|도|에)$");
    private static final Pattern BROAD_REQUEST = Pattern.compile("전부|모든|모두|전체");
    private static final Pattern PARAGRAPHS = Pattern.compile("\\R\\s*\\R");
    private static final Pattern SECTIONS = Pattern.compile("(?m)(?=^#{1,6}\\h)");
    private static final Set<String> GENERIC = Set.of(
            "회의",
            "기록",
            "문서",
            "자료",
            "관련",
            "내용",
            "결정",
            "조건",
            "알려줘",
            "알려주",
            "무엇",
            "언제",
            "어떤",
            "최근",
            "오늘",
            "어제",
            "지난주",
            "이번주"
    );

    private final List<String> terms;

    public SearchEvidenceRelevance(String question) {
        if (BROAD_REQUEST.matcher(question)
                .find()) {
            this.terms = List.of();
            return;
        }
        this.terms = WORD.matcher(question.toLowerCase(Locale.ROOT))
                .results()
                .map(
                        word -> KOREAN_SUFFIX.matcher(word.group())
                                .replaceFirst("")
                )
                .filter(word -> word.length() >= 2 && !GENERIC.contains(word))
                .distinct()
                .toList();
    }

    public boolean needsCandidate(List<SearchDocumentResult> primary) {
        return terms.size() >= 2 && primary.stream()
                .noneMatch(this::matches);
    }

    public boolean matches(SearchDocumentResult document) {
        return score(document.content()) >= 2;
    }

    Optional<String> excerpt(String content) {
        List<String> sections = Arrays.asList(SECTIONS.split(content));
        if (sections.size() == 1 && !sections.getFirst()
                .startsWith("#")) {
            sections = Arrays.asList(PARAGRAPHS.split(content));
        }
        return sections.stream()
                .map(String::strip)
                .filter(section -> score(section) >= 2)
                .max(Comparator.comparingInt(this::score));
    }

    private int score(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return (int) terms.stream()
                .filter(normalized::contains)
                .count();
    }
}
