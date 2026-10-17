package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class RecordingTranscriptionLoggingTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final UUID ATTEMPT = UUID.fromString("019bbbc0-51ae-7000-8000-000000000001");
    private static final String DOWNLOAD_URL = "https://immutable.example/audio?signature=PRIVATE_SIGNATURE";
    private static final RecordingTranscriptionExecution EXECUTION = new RecordingTranscriptionExecution(
            1,
            2,
            ATTEMPT,
            "private-storage-key",
            "private-version",
            "audio/webm",
            100,
            NOW
    );
    @Mock
    private RecordingTranscriptionStateService state;
    @Mock
    private TranscriptionClient client;
    @Mock
    private RecordingAudioStorage storage;
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final CapturingAppender appender = new CapturingAppender();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RecordingLog.class);
    private RecordingTranscriptionService service;
    private Map<String, String> previousMdc;

    @BeforeEach
    void captureLogs() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        appender.start();
        logger.addAppender(appender);
        when(state.findProcessing()).thenReturn(Optional.of(EXECUTION));
        when(
                storage.presignDownload(
                        "private-storage-key",
                        "private-version",
                        "audio/webm",
                        100
                )
        ).thenReturn(DOWNLOAD_URL);
        service = new RecordingTranscriptionService(
                state,
                new TranscriptionProperties(
                        true,
                        URI.create("http://localhost:18765"),
                        "private-token",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(60)
                ),
                client,
                storage,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    @AfterEach
    void stopWorkerAndCapture() {
        service.close();
        logger.detachAppender(appender);
        appender.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    @DisplayName("POST 시간 초과와 동일 시도 확인 성공을 별개 결과로 기록한다")
    void lostSubmitResponseIsUnconfirmedUntilSameAttemptIsConfirmed() throws Exception {
        // given
        when(
                client.submit(
                        ATTEMPT,
                        DOWNLOAD_URL
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AI_TIMEOUT));
        when(client.get(ATTEMPT)).thenReturn(succeeded());
        completeSignalsTerminal();

        // when
        runUntilTerminal();

        // then
        assertOutcomes(
                "submit",
                "started",
                "unconfirmed"
        );
        assertOutcomes(
                "confirm",
                "started",
                "confirmed"
        );
        assertOutcomes(
                "result",
                "started",
                "completed"
        );
        assertThat(fields("submit").getLast()).containsEntry(
                "errorCode",
                "AI_TIMEOUT"
        );
        verify(client).submit(
                ATTEMPT,
                DOWNLOAD_URL
        );
        verify(client).get(ATTEMPT);
        assertCorrelationAndSafeFields();
    }

    @ParameterizedTest
    @CsvSource({"STT_FAILED, STT_FAILED", "PRIVATE_FAILURE_TOKEN, UNKNOWN_FAILURE"})
    @DisplayName("확인된 AI 실패는 안전한 코드로 기록하고 전사본을 저장하지 않는다")
    void knownAiFailureIsRecordedWithoutExposingRemoteFailureText(
            String remoteCode,
            String loggedCode
    ) throws Exception {
        // given
        when(
                client.submit(
                        ATTEMPT,
                        DOWNLOAD_URL
                )
        ).thenReturn(
                new AiTranscriptionJob(
                        ATTEMPT,
                        AiTranscriptionJob.Status.FAILED,
                        remoteCode,
                        null
                )
        );
        doAnswer(call -> {
            terminal.countDown();
            return null;
        }).when(state)
                .fail(
                        EXECUTION,
                        remoteCode
                );

        // when
        runUntilTerminal();

        // then
        assertOutcomes(
                "result",
                "started",
                "failed"
        );
        assertThat(fields("result").getLast()).containsEntry(
                "errorCode",
                loggedCode
        );
        verify(
                state,
                never()
        ).complete(
                any(),
                any()
        );
        assertThat(appender.events)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_FAILURE_TOKEN"));
        assertCorrelationAndSafeFields();
    }

    @Test
    @DisplayName("AI 성공 후 저장 실패가 발생해도 원격 성공을 실패로 바꾸지 않는다")
    void successfulRemoteResultRemainsSuccessfulWhenDatabaseStorageFails() throws Exception {
        // given
        when(
                client.submit(
                        ATTEMPT,
                        DOWNLOAD_URL
                )
        ).thenReturn(succeeded());
        doThrow(new IllegalStateException("PRIVATE_TRANSCRIPT")).when(state)
                .complete(
                        EXECUTION,
                        transcript()
                );
        doAnswer(call -> {
            terminal.countDown();
            return null;
        }).when(state)
                .fail(
                        EXECUTION,
                        "RESULT_STORAGE_FAILED"
                );

        // when
        runUntilTerminal();

        // then
        assertOutcomes(
                "result",
                "started",
                "completed"
        );
        verify(state).fail(
                EXECUTION,
                "RESULT_STORAGE_FAILED"
        );
        assertThat(fields("persist")).isEmpty();
        assertCorrelationAndSafeFields();
    }

    @Test
    @DisplayName("worker 업무 기록에 깨운 HTTP 요청의 ID를 복사하지 않는다")
    void workerDoesNotInheritDispatchRequestId() throws Exception {
        // given
        MDC.put(
                "requestId",
                "dispatch-request-that-may-belong-to-another-recording"
        );
        when(
                client.submit(
                        ATTEMPT,
                        DOWNLOAD_URL
                )
        ).thenReturn(succeeded());
        completeSignalsTerminal();

        // when
        runUntilTerminal();

        // then
        assertThat(appender.events).isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.getMDCPropertyMap()).doesNotContainKey("requestId");
                    assertThat(fields(event)).containsEntry(
                            "requestId",
                            null
                    );
                });
        assertThat(MDC.get("requestId")).isEqualTo("dispatch-request-that-may-belong-to-another-recording");
    }

    @Test
    @DisplayName("반복 polling 횟수와 무관하게 업무 단계의 시작과 최종 결과만 기록한다")
    void normalPollingDoesNotProduceRepeatedBusinessEvents() throws Exception {
        // given
        AiTranscriptionJob processing = new AiTranscriptionJob(
                ATTEMPT,
                AiTranscriptionJob.Status.PROCESSING,
                null,
                null
        );
        when(
                client.submit(
                        ATTEMPT,
                        DOWNLOAD_URL
                )
        ).thenReturn(processing);
        when(client.get(ATTEMPT)).thenReturn(
                processing,
                processing,
                succeeded()
        );
        completeSignalsTerminal();

        // when
        runUntilTerminal();

        // then
        verify(
                client,
                times(3)
        ).get(ATTEMPT);
        assertOutcomes(
                "input",
                "started",
                "prepared"
        );
        assertOutcomes(
                "submit",
                "started",
                "accepted"
        );
        assertOutcomes(
                "result",
                "started",
                "completed"
        );
        assertThat(fields("confirm")).isEmpty();
        assertThat(appender.events).hasSize(6);
    }

    private void completeSignalsTerminal() {
        doAnswer(call -> {
            terminal.countDown();
            return null;
        }).when(state)
                .complete(
                        EXECUTION,
                        transcript()
                );
    }

    private void runUntilTerminal() throws Exception {
        service.dispatch();
        assertThat(
                terminal.await(
                        2,
                        TimeUnit.SECONDS
                )
        ).isTrue();
        service.close();
    }

    private void assertOutcomes(
            String stage,
            String... expected
    ) {
        assertThat(fields(stage)).extracting(value -> value.get("outcome"))
                .containsExactly(expected);
    }

    private void assertCorrelationAndSafeFields() {
        assertThat(appender.events).isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(fields(event)).containsEntry(
                            "recordingId",
                            2L
                    )
                            .containsEntry(
                                    "workspaceId",
                                    1L
                            )
                            .containsEntry(
                                    "attemptId",
                                    ATTEMPT.toString()
                            );
                    assertThat(event.getFormattedMessage()).doesNotContain(
                            "PRIVATE_SIGNATURE",
                            "PRIVATE_TRANSCRIPT",
                            "private-storage-key",
                            "private-version",
                            "private-token"
                    );
                });
    }

    private List<Map<String, Object>> fields(String stage) {
        return appender.events.stream()
                .map(this::fields)
                .filter(value -> ("recording.transcription." + stage).equals(value.get("event")))
                .toList();
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs()
                    .forEach(
                            pair -> fields.put(
                                    pair.key,
                                    pair.value
                            )
                    );
        }
        return fields;
    }

    private AiTranscriptionJob succeeded() {
        return new AiTranscriptionJob(
                ATTEMPT,
                AiTranscriptionJob.Status.SUCCEEDED,
                null,
                transcript()
        );
    }

    private AiTranscript transcript() {
        return new AiTranscript(
                1,
                "{\"transcription\":[]}",
                List.of(
                        new AiTranscript.Utterance(
                                0,
                                "화자 1",
                                0,
                                1,
                                "PRIVATE_TRANSCRIPT"
                        )
                )
        );
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }
}
