package com.knot.backend.document.application;

import java.util.List;
import java.util.Locale;

record CategoryRule(
        String name,
        String description,
        List<String> keywords
) {

    boolean matches(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .anyMatch(normalized::contains);
    }
}
