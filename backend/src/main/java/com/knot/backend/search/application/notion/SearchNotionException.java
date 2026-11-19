package com.knot.backend.search.application.notion;

import java.time.Duration;
import java.util.Optional;

/**
 * 노션 호출 실패. 메시지에는 사유 이름만 담고 토큰이나 노션 응답 본문을 싣지 않는다.
 */
public class SearchNotionException extends RuntimeException {

    private final SearchNotionFailure failure;
    private final Duration retryAfter;

    public SearchNotionException(SearchNotionFailure failure) {
        this(
                failure,
                null,
                null
        );
    }

    public SearchNotionException(
            SearchNotionFailure failure,
            Duration retryAfter,
            Throwable cause
    ) {
        super(
                failure.name(),
                cause
        );
        this.failure = failure;
        this.retryAfter = retryAfter;
    }

    public SearchNotionFailure failure() {
        return failure;
    }

    /**
     * RATE_LIMITED일 때 노션이 알려 준 대기 시간. 모르면 비어 있다.
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
