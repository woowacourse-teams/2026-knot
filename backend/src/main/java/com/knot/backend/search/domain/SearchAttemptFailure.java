package com.knot.backend.search.domain;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

public final class SearchAttemptFailure {
    public static final String NETWORK_ERROR = "SEARCH_NETWORK_ERROR";

    private static final String TRANSPORT_FAILED = "SEARCH_TRANSPORT_FAILED";
    private static final String TRANSPORT_TIMEOUT = "SEARCH_TRANSPORT_TIMEOUT";
    private static final String UPSTREAM_UNAVAILABLE = "SEARCH_UPSTREAM_UNAVAILABLE";
    public static final String DATABASE_UNAVAILABLE = "SEARCH_DATABASE_TEMPORARILY_UNAVAILABLE";

    private static final String FALLBACK_FAILED = "SEARCH_OPENAI_FALLBACK_FAILED";

    private SearchAttemptFailure() {}

    public static boolean temporary(RuntimeException error) {
        return isNetwork(error) || hasCode(
                error,
                UPSTREAM_UNAVAILABLE
        ) || hasCode(error,
                DATABASE_UNAVAILABLE
        );
    }

    public static boolean isNetwork(RuntimeException error) {
        if (!hasCode(
                error,
                TRANSPORT_FAILED
        ) && !hasCode(error,
                TRANSPORT_TIMEOUT
        )) {
            return false;
        }
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException) {
                return false;
            }
            if (cause instanceof IOException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    public static boolean terminalNetwork(RuntimeException error) {
        if (hasCode(
                error,
                FALLBACK_FAILED
        ) && error.getCause() instanceof RuntimeException cause) {
            return isNetwork(cause);
        }
        return isNetwork(error);
    }

    public static SearchToolException exhaustedFallback(RuntimeException cause) {
        return new SearchToolException(
                FALLBACK_FAILED,
                cause
        );
    }

    public static String upstreamStatusCode(int status) {
        if (status == 429) {
            return "SEARCH_RATE_LIMITED";
        }
        if (status >= 500 && status < 600) {
            return UPSTREAM_UNAVAILABLE;
        }
        return "SEARCH_REQUEST_REJECTED";
    }

    private static boolean hasCode(
            RuntimeException error,
            String code
    ) {
        return error instanceof SearchToolException tool && code.equals(tool.getCode());
    }
}
