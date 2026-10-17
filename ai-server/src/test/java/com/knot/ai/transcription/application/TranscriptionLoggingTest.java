package com.knot.ai.transcription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TranscriptionLoggingTest {
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TranscriptionLog.class);
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    };
    private final StubEngine engine = new StubEngine();
    private TranscriptionJobService service;

    @BeforeEach
    void prepare() {
        appender.start();
        logger.addAppender(appender);
        service = new TranscriptionJobService(engine);
    }

    @AfterEach
    void close() {
        engine.release.countDown();
        service.close();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void create_logsReplayAndRejectionWithoutPollNoise() throws Exception {
        // given
        UUID id = UUID.randomUUID();
        service.create(request(id));
        assertThat(
                engine.cleaning.await(
                        2,
                        TimeUnit.SECONDS
                )
        ).isTrue();

        // when
        service.create(request(id));
        assertThrows(
                TranscriptionException.class,
                () -> service.create(request(UUID.randomUUID()))
        );
        List<Map<String, Object>> beforePoll = fields();
        service.get(id);
        service.get(id);
        List<Map<String, Object>> afterPoll = fields();
        engine.release.countDown();
        awaitFinished(id);

        // then
        assertThat(afterPoll).isEqualTo(beforePoll);
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.accept"
                )
                        .containsEntry(
                                "outcome",
                                "replayed"
                        )
                        .containsEntry(
                                "attemptId",
                                id.toString()
                        )
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "outcome",
                        "rejected"
                )
                        .containsEntry(
                                "errorCode",
                                "BUSY"
                        )
        );
        assertThat(beforePoll).noneSatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.execute"
                )
                        .containsEntry(
                                "outcome",
                                "completed"
                        )
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.execute"
                )
                        .containsEntry(
                                "outcome",
                                "completed"
                        )
        );
        for (ILoggingEvent event : events) {
            Map<String, Object> values = fields(event);
            assertThat(values.get("elapsedMs")).isInstanceOf(Long.class);
            assertThat((Long) values.get("elapsedMs")).isNotNegative();
            JsonNode json = JsonMapper.builder()
                    .build()
                    .readTree(event.getFormattedMessage());
            assertThat(
                    json.path("event")
                            .asString()
            ).isEqualTo(values.get("event"));
            assertThat(
                    json.path("attemptId")
                            .asString()
            ).isEqualTo(values.get("attemptId"));
            assertThat(event.getThrowableProxy()).isNull();
        }
        assertSafe();
    }

    @Test
    void execute_logsKnownFailureOnlyAfterCleanup() throws Exception {
        // given
        engine.failure = new TranscriptionException(
                TranscriptionErrorCode.INFERENCE_TIMEOUT,
                new IllegalStateException("secret transcript token")
        );
        UUID id = UUID.randomUUID();
        service.create(request(id));
        assertThat(
                engine.cleaning.await(
                        2,
                        TimeUnit.SECONDS
                )
        ).isTrue();
        List<Map<String, Object>> beforeCleanup = fields();

        // when
        engine.release.countDown();
        JobSnapshot finished = awaitFinished(id);

        // then
        assertThat(beforeCleanup).noneSatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.execute"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
        );
        assertThat(finished.failureCode()).isEqualTo("INFERENCE_TIMEOUT");
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.execute"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "INFERENCE_TIMEOUT"
                        )
                        .containsEntry(
                                "exception",
                                TranscriptionException.class.getName()
                        )
                        .containsEntry(
                                "cause",
                                IllegalStateException.class.getName()
                        )
        );
        assertSafe();
    }

    @Test
    void cleanup_failurePreservesPublishedSuccessAndClosesAcceptance() throws Exception {
        // given
        engine.cleanupFailure = true;
        UUID id = UUID.randomUUID();

        // when
        service.create(request(id));
        engine.release.countDown();
        JobSnapshot finished = awaitFinished(id);
        TranscriptionException failure = assertThrows(
                TranscriptionException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertThat(finished.status()).isEqualTo(JobSnapshot.Status.SUCCEEDED);
        assertThat(service.isReady()).isFalse();
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.cleanup"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "PROCESS_CLEANUP_FAILED"
                        )
        );
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "event",
                        "recording.transcription.execute"
                )
                        .containsEntry(
                                "outcome",
                                "completed"
                        )
                        .doesNotContainKey("errorCode")
        );
        assertThat(failure.getCode()).isEqualTo("NOT_READY");
        assertThat(fields()).anySatisfy(
                fields -> assertThat(fields).containsEntry(
                        "outcome",
                        "rejected"
                )
                        .containsEntry(
                                "errorCode",
                                "NOT_READY"
                        )
        );
        assertSafe();
    }

    private void assertSafe() {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "secret",
                    "https://",
                    "X-Amz",
                    "안녕",
                    "rawWhisperJson"
            );
            assertThat(fields(event).keySet()).allMatch(
                    java.util.Set.of(
                            "event",
                            "outcome",
                            "attemptId",
                            "elapsedMs",
                            "errorCode",
                            "exception",
                            "cause",
                            "location"
                    )::contains
            );
        }
    }

    private List<Map<String, Object>> fields() {
        return events.stream()
                .map(this::fields)
                .toList();
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs()
                .stream()
                .collect(
                        Collectors.toMap(
                                pair -> pair.key,
                                pair -> pair.value
                        )
                );
    }

    private JobSnapshot awaitFinished(UUID id) throws InterruptedException {
        for (int index = 0; index < 200; index++) {
            JobSnapshot snapshot = service.get(id);
            if (snapshot.status() != JobSnapshot.Status.PROCESSING) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("작업이 완료되지 않았습니다");
    }

    private CreateTranscriptionCommand request(UUID id) {
        return new CreateTranscriptionCommand(
                id,
                "https://secret?X-Amz-Signature=secret",
                null
        );
    }

    private static class StubEngine implements TranscriptionEngine {
        private final CountDownLatch cleaning = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private RuntimeException failure;
        private boolean cleanupFailure;

        public boolean isReady() {
            return true;
        }

        public TranscriptResult transcribe(CreateTranscriptionCommand request) {
            if (failure != null) {
                throw failure;
            }
            return new TranscriptResult(
                    1,
                    "{\"transcription\":[]}",
                    List.of(
                            new TranscriptResult.Utterance(
                                    0,
                                    "화자 1",
                                    0,
                                    1,
                                    "secret transcript"
                            )
                    )
            );
        }

        public void cleanup(UUID id) throws InterruptedException {
            cleaning.countDown();
            release.await();
            if (cleanupFailure) {
                throw new IllegalStateException("secret cleanup URL");
            }
        }
    }
}
