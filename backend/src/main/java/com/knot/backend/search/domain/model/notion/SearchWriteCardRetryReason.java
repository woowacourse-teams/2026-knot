package com.knot.backend.search.domain.model.notion;

public enum SearchWriteCardRetryReason {
    NOTION_RECONNECT_REQUIRED,
    NOTION_CONNECTION_REQUIRED,
    NOTION_RATE_LIMITED,
    DATABASE_FIELDS_CHANGED
}
