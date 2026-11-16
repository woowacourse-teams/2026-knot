package com.knot.backend.search.presentation;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

final class SearchSseTiming {

    private static final Logger log = LoggerFactory.getLogger(SearchSseTiming.class);

    private final JsonMapper json;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private Duration firstDelta;
    private Duration terminalTime;
    private String terminalEvent;

    SearchSseTiming(JsonMapper json) {
        this(
                json,
                System::nanoTime
        );
    }

    SearchSseTiming(
            JsonMapper json,
            LongSupplier nanoTime
    ) {
        this.json = json;
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
    }

    String readToEnd(BufferedReader reader) throws IOException {
        StringJoiner stream = new StringJoiner("\n");
        String event = "";
        StringJoiner data = new StringJoiner("\n");
        for (String line; (line = reader.readLine()) != null;) {
            stream.add(line);
            if (line.startsWith("event:")) {
                event = line.substring("event:".length())
                        .strip();
            }
            if (line.startsWith("data:")) {
                data.add(line.substring("data:".length()));
            }
            if (line.isEmpty()) {
                observe(
                        event,
                        data.toString()
                );
                event = "";
                data = new StringJoiner("\n");
            }
        }
        return stream.toString();
    }

    private void observe(
            String event,
            String data
    ) {
        long receivedAt = nanoTime.getAsLong();
        if (firstDelta == null && "delta".equals(event) && !json.readTree(data)
                .path("text")
                .asString("")
                .isBlank()) {
            firstDelta = Duration.ofNanos(receivedAt - startedAt);
        }
        if (terminalEvent == null && isTerminal(event)) {
            terminalEvent = event;
            terminalTime = Duration.ofNanos(receivedAt - startedAt);
        }
    }

    private boolean isTerminal(String event) {
        return "completed".equals(event) || "failed".equals(event) || "stopped".equals(event);
    }

    Optional<Duration> firstDelta() {
        return Optional.ofNullable(firstDelta);
    }

    Optional<Duration> terminalTime() {
        return Optional.ofNullable(terminalTime);
    }

    Optional<String> terminalEvent() {
        return Optional.ofNullable(terminalEvent);
    }

    void log(String phase) {
        log.info(
                "search sse fixture phase={} firstDeltaMs={} terminalEvent={} terminalMs={}",
                phase,
                firstDelta().map(Duration::toMillis)
                        .orElse(null),
                terminalEvent,
                terminalTime().map(Duration::toMillis)
                        .orElse(null)
        );
    }
}
