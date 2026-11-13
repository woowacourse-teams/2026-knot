package com.knot.backend.search.domain;

/**
 * Keeps the existing external denial code while identifying the execution time
 * limit internally.
 */
public final class SearchScopeExpiredException extends SearchToolException {

    public SearchScopeExpiredException() {
        super("SEARCH_ACCESS_DENIED");
    }
}
