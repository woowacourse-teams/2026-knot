package com.knot.backend.search.presentation.stream;

import com.knot.backend.search.application.SearchAnswerSnapshotService;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.model.SearchProgressStage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
final class SearchAnswerSseConnection implements Runnable {

    private final SseEmitter emitter;
    private final SearchStreamReservation reservation;
    private final SearchAnswerSnapshotService snapshots;
    private final long workspaceId;
    private final long memberId;
    private final long answerId;
    private final Duration interval;
    private final long receiptId;
    private int sentLength;
    private SearchProgressStage sentStage;
    private boolean sentExcludedCount;
    private UUID executionToken;

    SearchAnswerSseConnection(
            SseEmitter emitter,
            SearchStreamReservation reservation,
            SearchAnswerSnapshotService snapshots,
            long workspaceId,
            long memberId,
            long answerId,
            Duration interval
    ) {
        this(
                emitter,
                reservation,
                snapshots,
                workspaceId,
                memberId,
                answerId,
                interval,
                0
        );
    }

    SearchAnswerSseConnection(
            SseEmitter emitter,
            SearchStreamReservation reservation,
            SearchAnswerSnapshotService snapshots,
            long workspaceId,
            long memberId,
            long answerId,
            Duration interval,
            long receiptId
    ) {
        this.emitter = emitter;
        this.reservation = reservation;
        this.snapshots = snapshots;
        this.workspaceId = workspaceId;
        this.memberId = memberId;
        this.answerId = answerId;
        this.interval = interval;
        this.receiptId = receiptId;
    }

    @Override
    public void run() {
        try {
            poll();
            emitter.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException exception) {
            emitter.completeWithError(exception);
        } finally {
            reservation.close();
        }
    }

    private void poll() throws IOException, InterruptedException {
        while (reservation.isActive()) {
            SearchAnswerSnapshotResult snapshot = snapshots.read(
                    workspaceId,
                    memberId,
                    answerId
            );
            if (snapshot.receiptId() != receiptId) {
                return;
            }
            if (changedExecution(snapshot.executionToken())) {
                return;
            }
            sendProgress(snapshot);
            sendExcludedCount(snapshot.excludedCount());
            sendContent(snapshot);
            if (snapshot.status() != SearchMessageStatus.STREAMING) {
                sendTerminal(snapshot);
                return;
            }
            reservation.awaitPoll(interval);
        }
    }

    private boolean changedExecution(UUID token) {
        if (token == null) {
            return false;
        }
        if (executionToken == null) {
            executionToken = token;
            return false;
        }
        return !executionToken.equals(token);
    }

    private void sendProgress(SearchAnswerSnapshotResult snapshot) throws IOException {
        SearchProgressStage stage = snapshot.stage();
        if (stage == null) {
            stage = SearchProgressStage.SEARCHING;
        }
        if (stage == sentStage) {
            return;
        }
        send(
                "progress",
                Map.of(
                        "stage",
                        stage.name()
                )
        );
        sentStage = stage;
    }

    private void sendExcludedCount(Integer count) throws IOException {
        if (count == null || sentExcludedCount) {
            return;
        }
        send(
                "excluded_documents",
                Map.of(
                        "count",
                        count
                )
        );
        sentExcludedCount = true;
    }

    private void sendContent(SearchAnswerSnapshotResult snapshot) throws IOException {
        String content = snapshot.content();
        if (content.length() < sentLength) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (content.length() == sentLength) {
            return;
        }
        boolean firstDelta = sentLength == 0;
        long sendStarted = System.nanoTime();
        try {
            send(
                    "delta",
                    Map.of(
                            "answerMessageId",
                            answerId,
                            "text",
                            content.substring(sentLength)
                    )
            );
            if (firstDelta) {
                logFirstDelta(
                        snapshot,
                        sendStarted,
                        "success"
                );
            }
        } catch (IOException exception) {
            if (firstDelta) {
                logFirstDelta(
                        snapshot,
                        sendStarted,
                        "failure"
                );
            }
            throw exception;
        }
        sentLength = content.length();
    }

    private void logFirstDelta(
            SearchAnswerSnapshotResult snapshot,
            long sendStarted,
            String outcome
    ) {
        String persistedAtToSendMs = "unavailable";
        if (snapshot.contentUpdatedAt() != null) {
            persistedAtToSendMs = Long.toString(
                    Math.max(
                            0,
                            Duration.between(
                                    snapshot.contentUpdatedAt(),
                                    Instant.now()
                            )
                                    .toMillis()
                    )
            );
        }
        log.info(
                "Search latency phase=sse_first_delta workspaceId={} answerId={} contentPersistedAtToSseSendMs={} sendElapsedMs={} outcome={}",
                workspaceId,
                answerId,
                persistedAtToSendMs,
                (System.nanoTime() - sendStarted) / 1_000_000,
                outcome
        );
    }

    private void sendTerminal(SearchAnswerSnapshotResult snapshot) throws IOException {
        if (snapshot.documentSelection() != null) {
            send(
                    "document_selection",
                    Map.of(
                            "answerMessageId",
                            answerId,
                            "selection",
                            snapshot.documentSelection()
                    )
            );
        }
        send(
                "evidence",
                Map.of(
                        "answerMessageId",
                        answerId,
                        "items",
                        snapshot.evidences()
                )
        );
        for (SearchNotionSseEvents.Event event : SearchNotionSseEvents.of(
                "answerMessageId",
                answerId,
                snapshot.notion()
        )) {
            send(
                    event.name(),
                    event.data()
            );
        }
        if (snapshot.status() == SearchMessageStatus.FAILED) {
            send(
                    "failed",
                    Map.of(
                            "answerMessageId",
                            answerId,
                            "status",
                            "FAILED",
                            "code",
                            failureCode(snapshot)
                    )
            );
            return;
        }
        if (snapshot.status() == SearchMessageStatus.STOPPED) {
            send(
                    "stopped",
                    Map.of(
                            "answerMessageId",
                            answerId,
                            "status",
                            "STOPPED"
                    )
            );
            return;
        }
        send(
                "completed",
                Map.of(
                        "answerMessageId",
                        answerId,
                        "status",
                        "COMPLETED"
                )
        );
    }

    private String failureCode(SearchAnswerSnapshotResult snapshot) {
        if (snapshot.failureCode() == null) {
            return "ANSWER_GENERATION_FAILED";
        }
        return snapshot.failureCode();
    }

    private void send(
            String event,
            Object data
    ) throws IOException {
        emitter.send(
                SseEmitter.event()
                        .name(event)
                        .data(
                                data,
                                MediaType.APPLICATION_JSON
                        )
        );
    }
}
