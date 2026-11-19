package com.knot.backend.search.presentation.stream;

import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.application.SearchAnswerSnapshotService;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class SearchAnswerReconnectConnection implements Runnable {

    private final SseEmitter emitter;
    private final SearchStreamReservation reservation;
    private final SearchAnswerSnapshotService snapshots;
    private final long workspaceId;
    private final long memberId;
    private final long conversationId;
    private final SearchAnswerSnapshotResult initial;
    private final Duration interval;
    private final long heartbeatNanos;
    private String sentContent = "";
    private List<SearchEvidenceItemResult> sentEvidence = List.of();
    private UUID executionToken;
    private long lastSentAt;

    SearchAnswerReconnectConnection(
            SseEmitter emitter,
            SearchStreamReservation reservation,
            SearchAnswerSnapshotService snapshots,
            long workspaceId,
            long memberId,
            long conversationId,
            SearchAnswerSnapshotResult initial,
            SearchWorkerProperties properties
    ) {
        this.emitter = emitter;
        this.reservation = reservation;
        this.snapshots = snapshots;
        this.workspaceId = workspaceId;
        this.memberId = memberId;
        this.conversationId = conversationId;
        this.initial = initial;
        this.interval = properties.getStreamPollInterval();
        this.heartbeatNanos = properties.getStreamHeartbeatInterval()
                .toNanos();
        this.executionToken = initial.executionToken();
    }

    @Override
    public void run() {
        try {
            restore();
            emitter.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            emitter.complete();
        } catch (IOException ignored) {
            // 쓰기 실패의 Servlet 종료 처리는 Spring에 맡기고 애플리케이션 예약만 해제한다.
        } catch (RuntimeException exception) {
            emitter.completeWithError(exception);
        } finally {
            reservation.close();
        }
    }

    private void restore() throws IOException, InterruptedException {
        if (!reservation.isActive()) {
            return;
        }
        send(
                "snapshot",
                Map.of(
                        "answerId",
                        initial.answerId(),
                        "content",
                        initial.content(),
                        "status",
                        initial.status()
                                .name()
                )
        );
        sentContent = initial.content();
        sendEvidenceIfChanged(initial);
        if (sendTerminal(initial)) {
            return;
        }
        poll();
    }

    private void poll() throws IOException, InterruptedException {
        while (reservation.isActive()) {
            SearchAnswerSnapshotResult snapshot = snapshots.read(
                    workspaceId,
                    memberId,
                    conversationId,
                    initial.answerId()
            );
            if (snapshot.receiptId() != initial.receiptId()) {
                return;
            }
            if (changedExecution(snapshot.executionToken())) {
                return;
            }
            if (!reservation.isActive()) {
                return;
            }
            sendDelta(snapshot.content());
            sendEvidenceIfChanged(snapshot);
            if (sendTerminal(snapshot)) {
                return;
            }
            sendHeartbeatIfDue();
            reservation.awaitPoll(interval);
        }
    }

    private boolean changedExecution(UUID token) {
        if (executionToken == null) {
            executionToken = token;
            return false;
        }
        return !executionToken.equals(token);
    }

    private void sendDelta(String content) throws IOException {
        if (!content.startsWith(sentContent)) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_STATE);
        }
        if (content.length() == sentContent.length()) {
            return;
        }
        send(
                "delta",
                Map.of(
                        "answerId",
                        initial.answerId(),
                        "text",
                        content.substring(sentContent.length())
                )
        );
        sentContent = content;
    }

    private void sendEvidenceIfChanged(SearchAnswerSnapshotResult snapshot) throws IOException {
        List<SearchEvidenceItemResult> evidence = snapshot.evidences();
        if (evidence.isEmpty() || evidence.equals(sentEvidence)) {
            return;
        }
        send(
                "evidence",
                Map.of(
                        "answerId",
                        initial.answerId(),
                        "items",
                        evidence
                )
        );
        sentEvidence = evidence;
    }

    private boolean sendTerminal(SearchAnswerSnapshotResult snapshot) throws IOException {
        if (snapshot.status() == SearchMessageStatus.STREAMING) {
            return false;
        }
        for (SearchNotionSseEvents.Event event : SearchNotionSseEvents.of(
                "answerId",
                initial.answerId(),
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
                            "answerId",
                            initial.answerId(),
                            "status",
                            "FAILED",
                            "code",
                            failureCode(snapshot)
                    )
            );
            return true;
        }
        send(
                snapshot.status()
                        .name()
                        .toLowerCase(Locale.ROOT),
                Map.of(
                        "answerId",
                        initial.answerId(),
                        "status",
                        snapshot.status()
                                .name()
                )
        );
        return true;
    }

    private String failureCode(SearchAnswerSnapshotResult snapshot) {
        if (snapshot.failureCode() == null) {
            return "ANSWER_GENERATION_FAILED";
        }
        return snapshot.failureCode();
    }

    private void sendHeartbeatIfDue() throws IOException {
        if (System.nanoTime() - lastSentAt < heartbeatNanos) {
            return;
        }
        emitter.send(
                SseEmitter.event()
                        .comment("keepalive")
        );
        lastSentAt = System.nanoTime();
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
        lastSentAt = System.nanoTime();
    }
}
