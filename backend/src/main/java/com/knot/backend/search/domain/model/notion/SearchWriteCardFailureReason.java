package com.knot.backend.search.domain.model.notion;

public enum SearchWriteCardFailureReason {
    LOCATION_PERMISSION_LOST,
    NOTION_CONNECTION_CHANGED,
    BODY_TOO_LONG,
    NOTION_NO_RESPONSE
}
