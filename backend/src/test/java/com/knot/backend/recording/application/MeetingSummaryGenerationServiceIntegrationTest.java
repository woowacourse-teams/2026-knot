package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.knot.backend.recording.application.dto.result.MeetingSummaryTask;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MeetingSummaryGenerationServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final String CONTENT = "## 한눈에 보기\n\n### 정한 방향\n- 정책을 유지한다.\n\n회의 핵심 결론: 정책을 유지한다.";

    private final MeetingSummaryGenerationService service;
    private final MeetingSummaryRetryService retries;
    private final JdbcClient jdbc;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private Clock clock;
    private long workspace;
    private long member;
    private long teammate;

    @Test
    void renew_keepsClaimBeyondOriginalLeaseAndRejectsOldOwnerAfterRestart() {
        long recording = registered();
        MeetingSummaryTask old = service.claim(recording)
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertThat(service.renew(old)).isTrue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(181));
        assertThat(service.claim(recording)).isEmpty();
        assertThat(service.renew(old)).isTrue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(361));
        MeetingSummaryTask next = service.claim(recording)
                .orElseThrow();
        assertThat(service.renew(old)).isFalse();
        service.succeed(
                old,
                CONTENT
        );
        assertThat(row(recording).get("status")).isEqualTo("RUNNING");
        service.succeed(
                next,
                CONTENT
        );
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void workerRenewsWhileGenerationIsBlocked() throws Exception {
        long recording = registered();
        MeetingSummaryTask task = service.claim(recording)
                .orElseThrow();
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(generator.generate(anyString())).thenAnswer(invocation -> {
            started.countDown();
            if (!finish.await(
                    45,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException("test timeout");
            }
            return CONTENT;
        });
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> execution = executor.submit(() -> worker.process(task));
            assertThat(
                    started.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            when(clock.instant()).thenReturn(NOW.plusSeconds(120));
            Thread.sleep(32_000);
            when(clock.instant()).thenReturn(NOW.plusSeconds(181));
            assertThat(service.claim(recording)).isEmpty();
            finish.countDown();
            execution.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
        } finally {
            finish.countDown();
            worker.shutdown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KNOT_LONG_LEASE_TEST", matches = "true")
    void workerKeepsLeaseForMoreThan180WallClockSeconds() throws Exception {
        long recording = registered();
        long startNanos = System.nanoTime();
        when(clock.instant()).thenAnswer(invocation -> NOW.plusNanos(System.nanoTime() - startNanos));
        MeetingSummaryTask task = service.claim(recording)
                .orElseThrow();
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenAnswer(invocation -> {
            Thread.sleep(TimeUnit.SECONDS.toMillis(185));
            return CONTENT;
        });
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> execution = executor.submit(() -> worker.process(task));
            execution.get(
                    205,
                    TimeUnit.SECONDS
            );
            assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
            assertThat(row(recording).get("attempt_no")).isEqualTo(1L);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void workerShutdownAllowsNewWorkerToRecoverExpiredClaim() throws Exception {
        long recording = registered();
        CountDownLatch started = new CountDownLatch(1);
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
                throw new LlmException(
                        LlmErrorCode.LLM_CALL_INTERRUPTED,
                        exception
                );
            }
            return CONTENT;
        });
        MeetingSummaryWorker oldWorker = new MeetingSummaryWorker(
                service,
                generator
        );
        try {
            oldWorker.poll();
            assertThat(
                    started.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            assertThat(row(recording).get("status")).isEqualTo("RUNNING");
        } finally {
            oldWorker.shutdown();
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(180));
        MeetingSummaryTask next = service.claim(recording)
                .orElseThrow();
        service.succeed(
                next,
                CONTENT
        );
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
        assertThat(row(recording).get("attempt_no")).isEqualTo(2L);
    }

    MeetingSummaryGenerationServiceIntegrationTest(
            MeetingSummaryGenerationService service,
            MeetingSummaryRetryService retries,
            JdbcClient jdbc
    ) {
        this.service = service;
        this.retries = retries;
        this.jdbc = jdbc;
        fixture = new RecordingHistoryFixture(jdbc);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        jdbc.sql("TRUNCATE recording_meeting_summary_activation")
                .update();
        when(clock.instant()).thenReturn(NOW);
        member = fixture.member("흑곰");
        teammate = fixture.member("이스타");
        workspace = fixture.workspace("요약 팀");
        fixture.join(
                workspace,
                member
        );
        fixture.join(
                workspace,
                teammate
        );
        service.registerEligible();
    }

    @Test
    @DisplayName("활성화 후 끝난 적격 녹음만 한 번 등록하고 문서 상태를 바꾸지 않는다")
    void registerEligible_success_onlyNewEligibleRecordings() {
        // given
        long eligible = recording(
                "TOPICS_REGISTERED",
                NOW
        );
        recording(
                "TOPICS_REGISTERED",
                NOW.minusSeconds(1000)
        );
        recording(
                "NO_CONTENT",
                NOW
        );
        recording(
                "WAITING_CLASSIFICATION",
                NOW
        );
        Map<String, Object> before = batch(eligible);
        // when
        service.registerEligible();
        service.registerEligible();
        // then
        assertThat(service.findRunnable()).containsExactly(eligible);
        assertThat(batch(eligible)).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 멤버가 요약만 재접수하고 성공한 요약은 보존한다")
    void retry_success_teammateAndSuccessPreserved() {
        // given
        long recording = registered();
        Map<String, Object> before = batch(recording);
        MeetingSummaryTask task = service.claim(recording)
                .orElseThrow();
        service.fail(
                task,
                "INVALID_RESPONSE",
                false
        );
        // when
        assertThat(
                retries.retry(
                        workspace,
                        teammate,
                        recording
                )
                        .recordingId()
        ).isEqualTo(recording);
        MeetingSummaryTask next = service.claim(recording)
                .orElseThrow();
        service.succeed(
                next,
                CONTENT
        );
        service.registerEligible();
        // then
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
        assertThat(row(recording).get("content")).isEqualTo(CONTENT);
        assertThat(batch(recording)).isEqualTo(before);
        assertThatThrownBy(
                () -> retries.retry(
                        workspace,
                        teammate,
                        recording
                )
        ).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("동시 재시도는 한 건만 접수하고 나머지는 거절한다")
    void retry_success_onlyOneConcurrentRequest() throws Exception {
        // given
        long recording = registered();
        service.fail(
                service.claim(recording)
                        .orElseThrow(),
                "INVALID_RESPONSE",
                false
        );
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> request = () -> {
            start.await(
                    5,
                    TimeUnit.SECONDS
            );
            try {
                retries.retry(
                        workspace,
                        teammate,
                        recording
                );
                return true;
            } catch (RecordingException exception) {
                return false;
            }
        };
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(request);
            Future<Boolean> two = executor.submit(request);
            start.countDown();
            List<Boolean> results = List.of(
                    one.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    two.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
            // then
            assertThat(results).containsExactlyInAnyOrder(
                    true,
                    false
            );
            assertThat(row(recording).get("user_retry_count")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("lease 만료 뒤 미완료 요약을 인계하고 이전 응답은 저장하지 않는다")
    void claim_success_restartAndLateResponse() {
        // given
        long recording = registered();
        MeetingSummaryTask old = service.claim(recording)
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(180));
        // when
        MeetingSummaryTask next = service.claim(recording)
                .orElseThrow();
        service.succeed(
                old,
                CONTENT
        );
        // then
        assertThat(row(recording).get("status")).isEqualTo("RUNNING");
        service.succeed(
                next,
                CONTENT
        );
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("Workspace 삭제 뒤 도착한 요약을 성공 공개하지 않는다")
    void succeed_failure_workspaceDeleted() {
        // given
        long recording = registered();
        MeetingSummaryTask task = service.claim(recording)
                .orElseThrow();
        jdbc.sql("UPDATE workspaces SET deleted_at=now() WHERE id=:id")
                .param(
                        "id",
                        workspace
                )
                .update();
        // when
        service.succeed(
                task,
                CONTENT
        );
        // then
        assertThat(row(recording).get("status")).isEqualTo("FAILED");
        assertThat(row(recording).get("content")).isNull();
    }

    @Test
    @DisplayName("전사 내용이 사라지면 실패 요약을 재접수하지 않는다")
    void retry_failure_blankTranscript() {
        // given
        long recording = registered();
        service.fail(
                service.claim(recording)
                        .orElseThrow(),
                "INVALID_RESPONSE",
                false
        );
        jdbc.sql("UPDATE transcripts SET content=:text WHERE recording_session_id=:id")
                .param(
                        "text",
                        "\u00a0"
                )
                .param(
                        "id",
                        recording
                )
                .update();
        // when & then
        assertThatThrownBy(
                () -> retries.retry(
                        workspace,
                        teammate,
                        recording
                )
        ).isInstanceOf(RecordingException.class);
        assertThat(row(recording).get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("사용자 재시도 기록과 원문은 과거 7일·3회 제한에 영향을 받지 않는다")
    void retry_success_pastLegacyLimits() {
        // given
        long recording = registered();
        service.fail(
                service.claim(recording)
                        .orElseThrow(),
                "INVALID_RESPONSE",
                false
        );
        jdbc.sql("UPDATE recording_meeting_summaries SET user_retry_count=9 WHERE recording_id=:id")
                .param(
                        "id",
                        recording
                )
                .update();
        when(clock.instant()).thenReturn(NOW.plusSeconds(900000));
        // when
        retries.retry(
                workspace,
                teammate,
                recording
        );
        // then
        assertThat(row(recording).get("status")).isEqualTo("QUEUED");
        assertThat(row(recording).get("user_retry_count")).isEqualTo(10L);
    }

    @Test
    @DisplayName("DB가 동일 녹음의 중복 요약과 불법 성공 상태를 거절한다")
    void persist_failure_databaseConstraints() {
        // given
        long recording = registered();
        // when & then
        assertThatThrownBy(
                () -> jdbc
                        .sql(
                                """
                                        INSERT INTO recording_meeting_summaries(recording_id,transcript_id,status,next_run_at,created_at)
                                        SELECT recording_id,transcript_id,'QUEUED',now(),now() FROM recording_meeting_summaries WHERE recording_id=:id
                                        """
                        )
                        .param(
                                "id",
                                recording
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                () -> jdbc.sql("UPDATE recording_meeting_summaries SET status='SUCCEEDED' WHERE recording_id=:id")
                        .param(
                                "id",
                                recording
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(row(recording).get("status")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("워커는 트랜잭션 밖에서 요약하고 실패해도 기존 문서 배치를 유지한다")
    void process_failure_isolatesExternalFailure() {
        // given
        long recording = registered();
        Map<String, Object> before = batch(recording);
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
        });
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        // when
        try {
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("QUEUED");
            assertThat(service.claim(recording)).isEmpty();
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
        } finally {
            worker.shutdown();
        }
        // then
        assertThat(row(recording).get("status")).isEqualTo("FAILED");
        assertThat(row(recording).get("attempt_no")).isEqualTo(2L);
        assertThat(batch(recording)).isEqualTo(before);
    }

    @Test
    @DisplayName("형식 오류 후 재시도에서 성공하면 요약 하나만 저장하고 문서 배치를 유지한다")
    void process_invalidResponse_thenSuccess() {
        long recording = registered();
        Map<String, Object> before = batch(recording);
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenThrow(new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE))
                .thenReturn(CONTENT);
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        try {
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("QUEUED");
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
            assertThat(row(recording).get("attempt_no")).isEqualTo(2L);
            assertThat(service.claim(recording)).isEmpty();
            assertThat(batch(recording)).isEqualTo(before);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    @DisplayName("시간 초과 후 재접수된 작업은 한 번 더 실행해 성공할 수 있다")
    void process_timeout_thenSuccess() {
        long recording = registered();
        Map<String, Object> before = batch(recording);
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT))
                .thenReturn(CONTENT);
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        try {
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("QUEUED");
            assertThat(service.claim(recording)).isEmpty();
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
            assertThat(row(recording).get("attempt_no")).isEqualTo(2L);
            assertThat(service.claim(recording)).isEmpty();
            assertThat(batch(recording)).isEqualTo(before);
        } finally {
            worker.shutdown();
        }
    }

    @Test
    @DisplayName("워커의 정상 요약 저장도 기존 문서 집계를 변경하지 않는다")
    void process_success_preservesDocumentBatch() {
        // given
        long recording = registered();
        Map<String, Object> before = batch(recording);
        MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
        when(generator.generate(anyString())).thenReturn(CONTENT);
        MeetingSummaryWorker worker = new MeetingSummaryWorker(
                service,
                generator
        );
        // when
        try {
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
        } finally {
            worker.shutdown();
        }
        // then
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
        assertThat(batch(recording)).isEqualTo(before);
    }

    @Test
    @DisplayName("동시 워커는 동일 녹음의 실행권을 한 번만 얻는다")
    void claim_success_oneConcurrentOwner() throws Exception {
        // given
        long recording = registered();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> request = () -> {
            start.await(
                    5,
                    TimeUnit.SECONDS
            );
            return service.claim(recording)
                    .isPresent();
        };
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(request);
            Future<Boolean> second = executor.submit(request);
            start.countDown();
            List<Boolean> results = List.of(
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
            // then
            assertThat(results).containsExactlyInAnyOrder(
                    true,
                    false
            );
            assertThat(row(recording).get("attempt_no")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("실제 완료 저장 오류는 롤백하고 lease 만료 후 복구할 수 있다")
    void succeed_failure_storageRollbackAndRecovery() {
        // given
        long recording = registered();
        MeetingSummaryTask task = service.claim(recording)
                .orElseThrow();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION reject_meeting_summary_success() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test storage failure'; END $$;
                CREATE TRIGGER reject_meeting_summary_success BEFORE UPDATE ON recording_meeting_summaries
                FOR EACH ROW WHEN (NEW.status='SUCCEEDED') EXECUTE FUNCTION reject_meeting_summary_success();
                """)
                .update();
        // when & then
        try {
            assertThatThrownBy(
                    () -> service.succeed(
                            task,
                            CONTENT
                    )
            ).isInstanceOf(DataAccessException.class);
            assertThat(row(recording).get("status")).isEqualTo("RUNNING");
            assertThat(row(recording).get("content")).isNull();
        } finally {
            jdbc.sql(
                    "DROP TRIGGER reject_meeting_summary_success ON recording_meeting_summaries; DROP FUNCTION reject_meeting_summary_success();"
            )
                    .update();
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(180));
        MeetingSummaryTask recovery = service.claim(recording)
                .orElseThrow();
        service.succeed(
                recovery,
                CONTENT
        );
        assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KNOT_LIVE_SUMMARY_TEST", matches = "true")
    void dockerPostgresAndLiveModelGenerateAndPersistSummary() {
        long recording = registered();
        jdbc.sql("UPDATE transcripts SET content=:text WHERE recording_session_id=:id")
                .param(
                        "text",
                        "녹음 품질을 측정하기로 합의했다. 검색 기능은 사용자 수가 1,000명을 넘으면 개발하기로 했다."
                )
                .param(
                        "id",
                        recording
                )
                .update();
        var properties = new com.knot.backend.global.config.LlmProperties();
        properties.setBaseUrl(java.net.URI.create(System.getenv("LLM_BASE_URL")));
        properties.setApiToken(System.getenv("LLM_API_TOKEN"));
        properties.setModel(System.getenv("LLM_MODEL"));
        properties.setRequestTimeout(java.time.Duration.ofSeconds(150));
        var client = new com.knot.backend.global.infrastructure.llm.LmStudioClient(
                java.net.http.HttpClient.newHttpClient(),
                new tools.jackson.databind.ObjectMapper(),
                properties
        );
        var generator = new com.knot.backend.recording.infrastructure.llm.LlmMeetingSummaryGenerator(
                client,
                new tools.jackson.databind.ObjectMapper()
        );
        var worker = new MeetingSummaryWorker(
                service,
                generator
        );
        try {
            worker.process(
                    service.claim(recording)
                            .orElseThrow()
            );
            assertThat(row(recording).get("status")).isEqualTo("SUCCEEDED");
            assertThat((String) row(recording).get("content")).isNotBlank();
        } finally {
            worker.shutdown();
        }
    }

    private long registered() {
        long id = recording(
                "TOPICS_REGISTERED",
                NOW
        );
        service.registerEligible();
        return id;
    }

    private long recording(
            String state,
            Instant started
    ) {
        long id = fixture.savedRecording(
                workspace,
                member,
                started
        );
        String processing = "FAILED";
        if ("NO_CONTENT".equals(state)) {
            processing = "NO_CONTENT";
        }
        fixture.batch(
                id,
                state,
                processing
        );
        return id;
    }

    private Map<String, Object> row(long recording) {
        return jdbc.sql("SELECT * FROM recording_meeting_summaries WHERE recording_id=:id")
                .param(
                        "id",
                        recording
                )
                .query()
                .singleRow();
    }

    private Map<String, Object> batch(long recording) {
        return jdbc.sql("SELECT * FROM document_generation_batches WHERE recording_session_id=:id")
                .param(
                        "id",
                        recording
                )
                .query()
                .singleRow();
    }
}
