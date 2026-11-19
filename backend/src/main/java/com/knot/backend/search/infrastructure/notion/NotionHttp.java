package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 노션 MCP와 토큰 엔드포인트 호출. 마감 시각까지 본문을 다 받지 못하면 TIMEOUT으로 끊는다.
 */
@Component
@RequiredArgsConstructor
class NotionHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Clock clock;

    HttpResponse<String> send(
            HttpRequest.Builder request,
            Instant deadline
    ) {
        Duration remaining = remaining(deadline);
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                request.timeout(remaining)
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        try {
            return pending.get(
                    remaining.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new SearchNotionException(
                    SearchNotionFailure.TIMEOUT,
                    null,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw failed(exception);
        } catch (ExecutionException exception) {
            throw transportFailure(exception);
        }
    }

    Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(
                clock.instant(),
                deadline
        );
        if (remaining.isNegative() || remaining.isZero()) {
            throw new SearchNotionException(SearchNotionFailure.TIMEOUT);
        }
        return remaining;
    }

    private static SearchNotionException transportFailure(ExecutionException exception) {
        if (exception.getCause() instanceof HttpTimeoutException) {
            return new SearchNotionException(
                    SearchNotionFailure.TIMEOUT,
                    null,
                    exception
            );
        }
        return failed(exception);
    }

    private static SearchNotionException failed(Exception exception) {
        return new SearchNotionException(
                SearchNotionFailure.FAILED,
                null,
                exception
        );
    }
}
