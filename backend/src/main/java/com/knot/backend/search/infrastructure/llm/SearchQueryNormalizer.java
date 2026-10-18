package com.knot.backend.search.infrastructure.llm;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class SearchQueryNormalizer {
    private static final List<String> QUERY_ENDINGS = List.of(
            "하기로",
            "하도록",
            "하려고",
            "됐나요",
            "됐나",
            "되었나요",
            "되었나",
            "인가요",
            "일까요",
            "인가",
            "일까",
            "되어",
            "돼"
    );
    private static final List<String> PARTICLES = List.of(
            "으로부터",
            "에게서",
            "에서는",
            "으로",
            "에서",
            "에게",
            "한테",
            "부터",
            "까지",
            "은",
            "는",
            "이",
            "가",
            "을",
            "를",
            "의",
            "에",
            "로",
            "와",
            "과",
            "도",
            "만",
            "랑"
    );
    private static final Set<String> QUESTION_FILLERS = Set.of(
            "있나",
            "있나요",
            "있어",
            "있어요",
            "있을까",
            "있을까요",
            "인가",
            "인가요",
            "일까",
            "일까요",
            "누구",
            "누가",
            "무엇",
            "무엇을",
            "뭐",
            "어디",
            "어디서",
            "언제",
            "왜",
            "어떻게",
            "어떤",
            "무슨"
    );
    private static final int MAX_QUERY_TERMS = 8;

    public String normalize(String question) {
        String normalized = Normalizer.normalize(
                question,
                Normalizer.Form.NFKC
        )
                .replaceAll(
                        "[\\p{P}\\p{S}]+",
                        " "
                );
        return String.join(
                " ",
                Arrays.stream(
                        normalized.strip()
                                .split("\\s+")
                )
                        .map(this::normalizeToken)
                        .filter(token -> !token.isBlank())
                        .filter(token -> !QUESTION_FILLERS.contains(token))
                        .distinct()
                        .limit(MAX_QUERY_TERMS)
                        .toList()
        );
    }

    private String normalizeToken(String token) {
        String withoutEnding = removeFirstMatchingEnding(
                token,
                QUERY_ENDINGS
        );
        return removeFirstMatchingEnding(
                withoutEnding,
                PARTICLES
        );
    }

    private String removeFirstMatchingEnding(
            String token,
            List<String> endings
    ) {
        for (String ending : endings) {
            if (hasMeaningfulStem(
                    token,
                    ending
            )) {
                return token.substring(
                        0,
                        token.length() - ending.length()
                );
            }
        }
        return token;
    }

    private boolean hasMeaningfulStem(
            String token,
            String ending
    ) {
        if (!token.endsWith(ending)) {
            return false;
        }
        String stem = token.substring(
                0,
                token.length() - ending.length()
        );
        return stem.codePointCount(
                0,
                stem.length()
        ) >= 2;
    }
}
