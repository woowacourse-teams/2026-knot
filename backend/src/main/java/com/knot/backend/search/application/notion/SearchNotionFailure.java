package com.knot.backend.search.application.notion;

public enum SearchNotionFailure {
    NOT_CONNECTED,
    CONNECTION_EXPIRED,
    RATE_LIMITED,
    PERMISSION_DENIED,
    NOT_FOUND,
    CONTENT_TOO_LONG,
    TIMEOUT,
    FAILED
}
