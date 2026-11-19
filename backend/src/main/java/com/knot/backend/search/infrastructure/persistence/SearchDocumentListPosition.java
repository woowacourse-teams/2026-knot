package com.knot.backend.search.infrastructure.persistence;

public record SearchDocumentListPosition(
        long id,
        int displayedThrough,
        int itemCount
) {
}
