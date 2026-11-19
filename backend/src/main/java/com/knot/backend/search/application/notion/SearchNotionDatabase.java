package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import java.util.List;

public record SearchNotionDatabase(
        String id,
        String title,
        String path,
        List<SearchWriteCardDatabaseFields.Property> properties
) {

    public SearchNotionDatabase {
        properties = List.copyOf(properties);
    }
}
