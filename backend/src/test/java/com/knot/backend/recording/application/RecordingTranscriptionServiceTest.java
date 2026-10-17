package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.*;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RemoteObservation;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingTranscriptionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Mock
    private RecordingTranscriptionStateService state;
    @Mock
    private TranscriptionClient client;
    @Mock
    private RecordingAudioStorage storage;
    private RecordingTranscriptionService service;

    @AfterEach
    void closeWorker() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    @DisplayName("비활성 전사는 대기열과 외부 서비스를 호출하지 않는다")
    void dispatch_success_disabled() {
        // given
        service = service(false);
        // when
        service.dispatch();
        // then
        verifyNoInteractions(
                state,
                client,
                storage
        );
    }

    @Test
    @DisplayName("새 작업은 최초 POST 직전 deadline을 저장하고 성공 snapshot을 즉시 채택한다")
    void dispatch_success_newAttempt() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.NOT_SUBMITTED,
                null
        );
        preparePost(execution);
        when(
                client.submit(
                        ATTEMPT,
                        "https://immutable.example/audio",
                        null
                )
        ).thenReturn(success());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
        org.mockito.InOrder order = inOrder(
                state,
                client
        );
        order.verify(state)
                .beginRemotePost(
                        eq(execution),
                        any()
                );
        order.verify(client)
                .submit(
                        ATTEMPT,
                        "https://immutable.example/audio",
                        null
                );
        verify(
                client,
                never()
        ).get(any());
    }

    @ParameterizedTest
    @EnumSource(value = RecordingErrorCode.class, names = {"AI_TIMEOUT", "AI_UNAVAILABLE"})
    @DisplayName("POST 응답 유실은 새 시도 없이 같은 ID를 GET으로 확인한다")
    void dispatch_success_responseUnknown(RecordingErrorCode code) {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.NOT_SUBMITTED,
                null
        );
        preparePost(execution);
        when(
                client.submit(
                        ATTEMPT,
                        "https://immutable.example/audio",
                        null
                )
        ).thenThrow(new RecordingException(code));
        when(client.get(ATTEMPT)).thenReturn(success());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
        verify(client).submit(
                ATTEMPT,
                "https://immutable.example/audio",
                null
        );
        verify(client).get(ATTEMPT);
    }

    @Test
    @DisplayName("재선점한 실행은 원격 GET 성공을 채택하고 POST하지 않는다")
    void dispatch_success_recoveredSucceeded() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.UNCONFIRMED,
                NOW.plusSeconds(20)
        );
        when(client.get(ATTEMPT)).thenReturn(success());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
        verifyNoInteractions(storage);
        verify(
                client,
                never()
        ).submit(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("원격 404는 같은 version과 시도 ID의 새 URL로 재접수한다")
    void dispatch_success_recoveredNotFound() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.UNCONFIRMED,
                NOW.plusSeconds(20)
        );
        preparePost(execution);
        when(client.get(ATTEMPT)).thenThrow(new RecordingException(RecordingErrorCode.AI_JOB_NOT_FOUND));
        when(
                client.submit(
                        ATTEMPT,
                        "https://immutable.example/audio",
                        null
                )
        ).thenReturn(success());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
        verify(storage).presignDownload(
                "key",
                "version",
                "audio/webm",
                1024
        );
        verify(client).submit(
                ATTEMPT,
                "https://immutable.example/audio",
                null
        );
    }

    @ParameterizedTest
    @EnumSource(value = RecordingErrorCode.class, names = {"AI_BUSY", "AI_NOT_READY"})
    @DisplayName("명시적인 최초 접수 거절은 준비 대기로 돌리고 사용자 실패로 확정하지 않는다")
    void dispatch_success_readinessRequeue(RecordingErrorCode code) {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.NOT_SUBMITTED,
                null
        );
        preparePost(execution);
        when(
                client.submit(
                        ATTEMPT,
                        "https://immutable.example/audio",
                        null
                )
        ).thenThrow(new RecordingException(code));
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).deferReadiness(
                execution,
                Duration.ofSeconds(120),
                true
        );
        verify(
                state,
                never()
        ).fail(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("원격 terminal 실패는 동일 attempt POST로 재실행하지 않는다")
    void dispatch_failure_remoteTerminal() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.UNCONFIRMED,
                NOW.plusSeconds(20)
        );
        when(client.get(ATTEMPT)).thenReturn(
                new AiTranscriptionJob(
                        ATTEMPT,
                        AiTranscriptionJob.Status.FAILED,
                        "INFERENCE_FAILED",
                        null
                )
        );
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).fail(
                execution,
                "INFERENCE_FAILED"
        );
        verify(
                client,
                never()
        ).submit(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("기한이 지나도 마지막 GET 성공 결과는 채택한다")
    void dispatch_success_lastGetSucceeded() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.PROCESSING,
                NOW
        );
        when(client.get(ATTEMPT)).thenReturn(success());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
    }

    @Test
    @DisplayName("기한이 지난 원격 실행은 결과 미확정으로 저장하고 POST하지 않는다")
    void dispatch_failure_deadlineProcessing() {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.PROCESSING,
                NOW
        );
        when(client.get(ATTEMPT)).thenReturn(
                new AiTranscriptionJob(
                        ATTEMPT,
                        AiTranscriptionJob.Status.PROCESSING,
                        null,
                        null
                )
        );
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000)
        ).fail(
                execution,
                "AI_OUTCOME_UNKNOWN"
        );
        verify(
                client,
                never()
        ).submit(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("소유권이나 녹음 가용성을 잃으면 외부 AI를 호출하지 않는다")
    void dispatch_success_lostOwnership() {
        // given
        RecordingTranscriptionExecution execution = execution(
                RemoteObservation.UNCONFIRMED,
                NOW.plusSeconds(20)
        );
        when(
                state.claimNext(
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.of(execution),
                Optional.empty()
        );
        when(state.prepare(execution)).thenReturn(Optional.empty());
        service = service(true);
        // when
        service.dispatch();
        // then
        verify(
                state,
                timeout(2000).atLeast(2)
        ).claimNext(
                any(),
                any()
        );
        verifyNoInteractions(
                client,
                storage
        );
    }

    @Test
    @DisplayName("동일 프로세스의 중복 dispatch는 실행 중 worker를 하나만 유지한다")
    void dispatch_success_singleLocalWorker() throws Exception {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.UNCONFIRMED,
                NOW.plusSeconds(20)
        );
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.get(ATTEMPT)).thenAnswer(call -> {
            entered.countDown();
            release.await(
                    2,
                    TimeUnit.SECONDS
            );
            return success();
        });
        service = service(true);
        service.dispatch();
        assertThat(
                entered.await(
                        2,
                        TimeUnit.SECONDS
                )
        ).isTrue();
        // when
        service.dispatch();
        // then
        verify(state).claimNext(
                any(),
                any()
        );
        release.countDown();
        verify(
                state,
                timeout(2000)
        ).complete(
                execution,
                transcript()
        );
    }

    @Test
    @DisplayName("정상 종료는 현재 owner 작업을 같은 시도로 다시 대기시킨다")
    void close_success_requeuesOwnedAttempt() throws Exception {
        // given
        RecordingTranscriptionExecution execution = prepare(
                RemoteObservation.PROCESSING,
                NOW.plusSeconds(20)
        );
        CountDownLatch entered = new CountDownLatch(1);
        when(client.get(ATTEMPT)).thenAnswer(call -> {
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
                throw new RecordingException(
                        RecordingErrorCode.AI_INTERRUPTED,
                        exception
                );
            }
            return success();
        });
        service = service(true);
        service.dispatch();
        assertThat(
                entered.await(
                        2,
                        TimeUnit.SECONDS
                )
        ).isTrue();
        // when
        service.close();
        // then
        verify(state).requeue(
                execution,
                Duration.ZERO
        );
    }

    @Test
    @DisplayName("미확정 실패 재시도는 원격 실행을 확인하고 같은 attempt 관측으로 복귀한다")
    void retry_success_resumesUncertainAttempt() {
        // given
        RecordingTranscriptionExecution previous = execution(
                RemoteObservation.UNCONFIRMED,
                NOW
        );
        when(
                state.findUncertainRetry(
                        1,
                        2,
                        3
                )
        ).thenReturn(Optional.of(previous));
        when(client.get(ATTEMPT)).thenReturn(
                new AiTranscriptionJob(
                        ATTEMPT,
                        AiTranscriptionJob.Status.PROCESSING,
                        null,
                        null
                )
        );
        RecordingTranscriptionRetryResult queued = new RecordingTranscriptionRetryResult(
                3,
                ATTEMPT,
                com.knot.backend.recording.domain.model.TranscriptionStatus.QUEUED
        );
        when(
                state.resolveUncertainRetry(
                        1,
                        2,
                        previous,
                        true
                )
        ).thenReturn(queued);
        service = service(true);
        // when
        RecordingTranscriptionRetryResult result = service.retry(
                1,
                2,
                3
        );
        // then
        assertThat(result.attemptId()).isEqualTo(ATTEMPT);
        verify(
                state,
                never()
        ).retry(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(
                client,
                never()
        ).submit(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("미확정 실패가 원격에서 사라진 것을 확인한 사용자 재시도만 새 attempt를 만든다")
    void retry_success_newAttemptAfterNotFound() {
        // given
        RecordingTranscriptionExecution previous = execution(
                RemoteObservation.UNCONFIRMED,
                NOW
        );
        when(
                state.findUncertainRetry(
                        1,
                        2,
                        3
                )
        ).thenReturn(Optional.of(previous));
        when(client.get(ATTEMPT)).thenThrow(new RecordingException(RecordingErrorCode.AI_JOB_NOT_FOUND));
        RecordingTranscriptionRetryResult queued = new RecordingTranscriptionRetryResult(
                3,
                UUID.randomUUID(),
                com.knot.backend.recording.domain.model.TranscriptionStatus.QUEUED
        );
        when(
                state.resolveUncertainRetry(
                        1,
                        2,
                        previous,
                        false
                )
        ).thenReturn(queued);
        service = service(true);
        // when
        RecordingTranscriptionRetryResult result = service.retry(
                1,
                2,
                3
        );
        // then
        assertThat(result.attemptId()).isNotEqualTo(ATTEMPT);
        verify(state).resolveUncertainRetry(
                1,
                2,
                previous,
                false
        );
    }

    @Test
    @DisplayName("미확정 시도를 조회할 수 없으면 새 접수를 성공으로 반환하지 않는다")
    void retry_failure_uncertainRemoteUnavailable() {
        // given
        RecordingTranscriptionExecution previous = execution(
                RemoteObservation.UNCONFIRMED,
                NOW
        );
        when(
                state.findUncertainRetry(
                        1,
                        2,
                        3
                )
        ).thenReturn(Optional.of(previous));
        when(client.get(ATTEMPT)).thenThrow(new RecordingException(RecordingErrorCode.AI_UNAVAILABLE));
        service = service(true);
        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.retry(
                        1,
                        2,
                        3
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        verify(
                state,
                never()
        ).resolveUncertainRetry(
                anyLong(),
                anyLong(),
                any(),
                anyBoolean()
        );
        verify(
                state,
                never()
        ).claimNext(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("미확정 성공 조회의 snapshot을 다시 GET하지 않고 채택한다")
    void retry_success_adoptsObservedSnapshot() {
        // given
        RecordingTranscriptionExecution previous = execution(
                RemoteObservation.UNCONFIRMED,
                NOW
        );
        when(
                state.findUncertainRetry(
                        1,
                        2,
                        3
                )
        ).thenReturn(Optional.of(previous));
        when(client.get(ATTEMPT)).thenReturn(success());
        RecordingTranscriptionRetryResult adopted = new RecordingTranscriptionRetryResult(
                3,
                ATTEMPT,
                com.knot.backend.recording.domain.model.TranscriptionStatus.SUCCEEDED
        );
        when(
                state.adoptUncertainResult(
                        1,
                        2,
                        previous,
                        transcript()
                )
        ).thenReturn(adopted);
        service = service(true);
        // when
        RecordingTranscriptionRetryResult result = service.retry(
                1,
                2,
                3
        );
        // then
        assertThat(result.status()).isEqualTo(com.knot.backend.recording.domain.model.TranscriptionStatus.SUCCEEDED);
        verify(client).get(ATTEMPT);
        verify(
                state,
                never()
        ).resolveUncertainRetry(
                anyLong(),
                anyLong(),
                any(),
                anyBoolean()
        );
    }

    private RecordingTranscriptionExecution prepare(
            RemoteObservation observation,
            Instant deadline
    ) {
        RecordingTranscriptionExecution execution = execution(
                observation,
                deadline
        );
        when(
                state.claimNext(
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.of(execution),
                Optional.empty()
        );
        when(state.prepare(execution)).thenReturn(Optional.of(execution));
        return execution;
    }

    private void preparePost(RecordingTranscriptionExecution execution) {
        when(
                storage.presignDownload(
                        "key",
                        "version",
                        "audio/webm",
                        1024
                )
        ).thenReturn("https://immutable.example/audio");
        when(
                state.beginRemotePost(
                        eq(execution),
                        any()
                )
        ).thenReturn(Optional.of(NOW.plusSeconds(20)));
    }

    private RecordingTranscriptionExecution execution(
            RemoteObservation observation,
            Instant deadline
    ) {
        return new RecordingTranscriptionExecution(
                1,
                2,
                ATTEMPT,
                "key",
                "version",
                "audio/webm",
                1024,
                NOW,
                UUID.randomUUID(),
                1,
                deadline,
                observation,
                null
        );
    }

    private RecordingTranscriptionService service(boolean enabled) {
        return new RecordingTranscriptionService(
                state,
                new TranscriptionProperties(
                        enabled,
                        URI.create("http://localhost"),
                        "token",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(20)
                ),
                client,
                storage,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    private AiTranscriptionJob success() {
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
                "원문",
                List.of()
        );
    }
}
