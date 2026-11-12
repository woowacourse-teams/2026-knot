package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.global.logging.ServerErrorLogger;

import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.domain.SearchToolException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@lombok.extern.slf4j.Slf4j
public class SearchAgentHttp implements AutoCloseable {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long byteLimit;
    private final ScheduledThreadPoolExecutor deadlines;
    private final SearchTransportCancellation cancellations;

    public SearchAgentHttp(
            HttpClient client,
            ObjectMapper mapper,
            Clock clock,
            long byteLimit,
            SearchTransportCancellation cancellations
    ) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        this.byteLimit = byteLimit;
        this.cancellations = cancellations;
        deadlines = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .factory()
        );
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public JsonNode json(
            URI uri,
            String bearer,
            Object body,
            SearchToolScope scope,
            Map<String, String> headers
    ) {
        StringBuilder result = new StringBuilder();
        exchange(
                uri,
                bearer,
                body,
                scope,
                headers,
                input -> {
                    try {
                        byte[] bytes = input.readNBytes(Math.toIntExact(byteLimit + 1));
                        if (bytes.length > byteLimit) {
                            throw new SearchToolException("TRANSPORT_RESPONSE_TOO_LARGE");
                        }
                        result.append(
                                new String(
                                        bytes,
                                        StandardCharsets.UTF_8
                                )
                        );
                    } catch (IOException exception) {
                        throw new SearchToolException(
                                "SEARCH_TRANSPORT_FAILED",
                                exception
                        );
                    }
                },
                200
        );
        return mapper.readTree(result.toString());
    }

    public void stream(
            URI uri,
            String bearer,
            Object body,
            SearchToolScope scope,
            Consumer<JsonNode> consumer
    ) {
        exchange(
                uri,
                bearer,
                body,
                scope,
                Map.of(
                        "Accept",
                        "text/event-stream"
                ),
                input -> readEvents(
                        input,
                        consumer
                ),
                200
        );
    }

    public void notification(
            URI uri,
            String bearer,
            Object body,
            SearchToolScope scope,
            Map<String, String> headers
    ) {
        exchange(
                uri,
                bearer,
                body,
                scope,
                headers,
                input -> {
                },
                202
        );
    }

    private void readEvents(
            InputStream input,
            Consumer<JsonNode> consumer
    ) {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        long count = 0;
        boolean completed = false;
        try {
            int next;
            while ((next = input.read()) != -1) {
                if (++count > byteLimit) {
                    throw new SearchToolException("TRANSPORT_RESPONSE_TOO_LARGE");
                }
                if (next != '\n') {
                    line.write(next);
                    continue;
                }
                String text = line.toString(StandardCharsets.UTF_8)
                        .stripTrailing();
                line.reset();
                if (!text.startsWith("data:")) {
                    continue;
                }
                String data = text.substring(5)
                        .stripLeading();
                if ("[DONE]".equals(data)) {
                    completed = true;
                    break;
                }
                if (!data.isBlank()) {
                    consumer.accept(mapper.readTree(data));
                }
            }
        } catch (IOException exception) {
            throw new SearchToolException(
                    "SEARCH_TRANSPORT_FAILED",
                    exception
            );
        }
        if (!completed) {
            throw new SearchToolException("INCOMPLETE_MODEL_STREAM");
        }
    }

    private void exchange(
            URI uri,
            String bearer,
            Object body,
            SearchToolScope scope,
            Map<String, String> headers,
            Consumer<InputStream> reader,
            int expectedStatus
    ) {
        Duration remaining = remaining(scope);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(remaining)
                .header(
                        "Authorization",
                        "Bearer " + bearer
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "User-Agent",
                        "Knot-Llm/1.0"
                )
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        headers.forEach(builder::header);
        cancellations.validate(scope);
        CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        try (SearchTransportRegistration registration = cancellations.watch(
                scope,
                pending
        )) {
            HttpResponse<InputStream> response = pending.get(
                    remaining(scope).toMillis(),
                    TimeUnit.MILLISECONDS
            );
            try (InputStream input = new java.io.BufferedInputStream(response.body())) {
                if (response.statusCode() != expectedStatus) {
                    int status = response.statusCode();
                    log.warn(
                            "Search provider response status={} answerId={}",
                            status,
                            scope.answerId()
                    );
                    // Rate limits may include long Retry-After values. Do not immediately repeat
                    // them.
                    String code = status == 429
                            ? "SEARCH_RATE_LIMITED"
                            : status >= 500 ? "SEARCH_UPSTREAM_UNAVAILABLE" : "SEARCH_REQUEST_REJECTED";
                    throw new SearchToolException(code);
                }
                ScheduledFuture<?> timeout = deadlines.schedule(
                        () -> closeInput(input),
                        remaining(scope).toMillis(),
                        TimeUnit.MILLISECONDS
                );
                try {
                    reader.accept(input);
                    remaining(scope);
                } finally {
                    timeout.cancel(false);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new SearchToolException(
                    "SEARCH_TRANSPORT_INTERRUPTED",
                    exception
            );
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new SearchToolException(
                    "SEARCH_TRANSPORT_TIMEOUT",
                    exception
            );
        } catch (ExecutionException | CancellationException exception) {
            throw new SearchToolException(
                    "SEARCH_TRANSPORT_FAILED",
                    exception
            );
        } catch (IOException exception) {
            throw new SearchToolException(
                    "SEARCH_TRANSPORT_FAILED",
                    exception
            );
        }
    }

    private Duration remaining(SearchToolScope scope) {
        Duration duration = Duration.between(
                clock.instant(),
                scope.expiresAt()
        );
        if (duration.isNegative() || duration.isZero()) {
            throw new SearchToolException("SEARCH_TRANSPORT_TIMEOUT");
        }
        return duration;
    }

    private void closeInput(InputStream input) {
        try {
            input.close();
        } catch (IOException exception) {
            ServerErrorLogger.debug(
                    log,
                    "Search transport phase=timeout-cleanup",
                    exception
            );
        }
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
    }
}
