package com.knot.backend.search.application.notion;

public record SearchNotionPage(
        String id,
        String title,
        String url,
        String content
) {
}
