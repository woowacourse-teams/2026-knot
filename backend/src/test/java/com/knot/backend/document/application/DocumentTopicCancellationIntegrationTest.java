package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false",
        "knot.document-topic-reorganization.enabled=true"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentTopicCancellationIntegrationTest {
    private static final Instant NOW = Instant.now()
            .truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private DocumentTopicCancellationService cancellation;
    @Autowired
    private DocumentTopicReorganizationService start;
    @Autowired
    private DocumentTopicCheckpointService checkpoints;
    @Autowired
    private DocumentTopicApplyService apply;
    @Autowired
    private DocumentTopicWorker worker;
    @Autowired
    private DocumentTopicStatusService status;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient client;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private LlmDocumentTopicPlanner planner;
    @MockitoBean
    private DocumentTopicScheduler scheduler;
    private DocumentFixtures fixtures;
    private long workspace;
    private long owner;
    private long category;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE members,workspaces RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(NOW);
        fixtures = new DocumentFixtures(client);
        owner = fixtures.saveMember("OWNER");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                owner
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE workspace_id=?",
                workspace
        );
        category = jdbc.queryForObject(
                "INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at) VALUES (?,'녹음·전사','녹음·전사','녹음 품질','NORMAL',?) RETURNING id",
                Long.class,
                workspace,
                Timestamp.from(NOW)
        );
        when(planner.plan(any())).thenAnswer(invocation -> plan(invocation.getArgument(0)));
        document();
    }

    @Test
    @DisplayName("실행 중 AI 응답을 기다리지 않고 취소 완료하며 늦은 성공 응답은 폐기한다")
    void cancel_success_lateAiResponse() throws Exception {
        // given
        long id = start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(
                    release.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            return plan(invocation.getArgument(0));
        }).when(planner)
                .plan(any());
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(
                    () -> worker.execute(
                            workspace,
                            id
                    )
            );
            assertThat(
                    entered.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            try {
                // when
                cancellation.request(
                        workspace,
                        owner,
                        id
                );
                cancellation.complete(
                        workspace,
                        id
                );
                assertThat(state(id)).isEqualTo("CANCELLED");
            } finally {
                release.countDown();
            }
            future.get(
                    10,
                    TimeUnit.SECONDS
            );
        }
        // then
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                        Integer.class
                )
        ).isZero();
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operation_targets",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    @DisplayName("취소 완료와 준비 자료 삭제는 함께 롤백하고 재실행할 수 있다")
    void complete_success_recoversCleanupFailure() {
        // given
        long id = start();
        cancellation.request(
                workspace,
                owner,
                id
        );
        jdbc.execute(
                "CREATE FUNCTION reject_cancellation_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'cleanup failure'; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_cancellation BEFORE DELETE ON document_topic_operation_targets FOR EACH ROW EXECUTE FUNCTION reject_cancellation_cleanup()"
        );
        try {
            // when & then
            assertThatThrownBy(
                    () -> cancellation.complete(
                            workspace,
                            id
                    )
            ).isInstanceOf(DataAccessException.class);
            assertThat(state(id)).isEqualTo("CANCEL_REQUESTED");
        } finally {
            jdbc.execute("DROP TRIGGER reject_cancellation ON document_topic_operation_targets");
            jdbc.execute("DROP FUNCTION reject_cancellation_cleanup()");
        }
        cancellation.complete(
                workspace,
                id
        );
        assertThat(state(id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("취소 접수와 적용 진입의 두 트랜잭션 중 먼저 확정한 취소만 성공한다")
    void beginApply_failure_cancellationWins() throws Exception {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancel = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                cancellation.request(
                        workspace,
                        owner,
                        id
                );
                locked.countDown();
                await(release);
            }));
            assertThat(
                    locked.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<Boolean> begin = executor.submit(() -> checkpoints.beginApply(execution));
            release.countDown();
            cancel.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(
                    begin.get(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isFalse();
        }
        assertThat(state(id)).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    @DisplayName("APPLYING을 먼저 확정하면 취소가409이며 공개 배정은 성공 전까지 유지된다")
    void request_failure_applyingWins() {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        // when
        assertThat(checkpoints.beginApply(execution)).isTrue();
        // then
        assertThatThrownBy(
                () -> cancellation.request(
                        workspace,
                        owner,
                        id
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(state(id)).isEqualTo("APPLYING");
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                        Integer.class
                )
        ).isZero();
        apply.apply(execution);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("적용 경계 뒤 중단은 만료 lease를 새 토큰으로 인계하고 한 번만 반영한다")
    void claim_success_recoversApplyingWithoutLlm() {
        // given
        long id = start();
        DocumentTopicExecution previous = prepare(id);
        checkpoints.beginApply(previous);
        reset(planner);
        when(clock.instant()).thenReturn(NOW.plusSeconds(91));
        // when
        DocumentTopicExecution next = checkpoints.claim(
                workspace,
                id
        )
                .orElseThrow();
        // then
        assertThat(next.token()).isNotEqualTo(previous.token());
        assertThat(state(id)).isEqualTo("APPLYING");
        assertThatThrownBy(() -> apply.apply(previous)).isInstanceOf(DocumentException.class);
        apply.apply(next);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        org.mockito.Mockito.verifyNoInteractions(planner);
    }

    @Test
    @DisplayName("취소 뒤 새 작업이 시작되어도 오래된 워커 실패가 새 결과와 복원본을 변경하지 않는다")
    void cancel_success_keepsPreviousRecoveryAndNewDocuments() {
        // given
        long first = start();
        for (int step = 0; step < 3; step++) {
            worker.execute(
                    workspace,
                    first
            );
        }
        Long recovery = status.find(
                workspace,
                owner
        )
                .restorableVersion()
                .versionId();
        long next = start();
        DocumentTopicExecution old = checkpoints.claim(
                workspace,
                next
        )
                .orElseThrow();
        long created = document();
        // when
        cancellation.request(
                workspace,
                owner,
                next
        );
        cancellation.complete(
                workspace,
                next
        );
        checkpoints.fail(
                old,
                "LATE_FAILURE",
                true
        );
        // then
        assertThat(state(next)).isEqualTo("CANCELLED");
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .canRestore()
        ).isTrue();
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .restorableVersion()
                        .versionId()
        ).isEqualTo(recovery);
        assertThat(
                jdbc.queryForObject(
                        "SELECT category_id FROM documents WHERE id=?",
                        Long.class,
                        created
                )
        ).isNull();
        long latest = start();
        checkpoints.fail(
                old,
                "OLDER_FAILURE",
                false
        );
        assertThat(state(latest)).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("접수 후 OWNER가 바뀌면 새 OWNER만 관리하고 서버 재시작 뒤에도 취소를 끝낸다")
    void cancel_success_ownerTransferAndRestart() {
        // given
        long id = start();
        long next = fixtures.saveMember("NEW OWNER");
        fixtures.join(
                workspace,
                next
        );
        jdbc.update(
                "UPDATE workspace_members SET role='MEMBER' WHERE workspace_id=?",
                workspace
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE member_id=?",
                next
        );
        // when & then
        assertThatThrownBy(
                () -> cancellation.request(
                        workspace,
                        owner,
                        id
                )
        ).isInstanceOf(com.knot.backend.workspace.domain.WorkspaceException.class);
        cancellation.request(
                workspace,
                next,
                id
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(1900));
        checkpoints.fail(
                new DocumentTopicExecution(
                        workspace,
                        id,
                        UUID.randomUUID(),
                        NOW.plusSeconds(1800),
                        false,
                        java.util.List.of(),
                        java.util.List.of()
                ),
                "TIMEOUT",
                false
        );
        assertThat(state(id)).isEqualTo("CANCEL_REQUESTED");
        cancellation.complete(
                workspace,
                id
        );
        assertThat(
                cancellation.request(
                        workspace,
                        next,
                        id
                )
                        .status()
        ).isEqualTo(DocumentTopicOperationStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = com.knot.backend.global.exception.LlmErrorCode.class, names = {"LLM_TIMEOUT",
            "LLM_INPUT_LIMIT_EXCEEDED", "LLM_UNAVAILABLE"})
    @DisplayName("취소 뒤 도착한 AI 오류는 재시도·배치 분할 호출을 시작하지 않는다")
    void cancel_success_discardsLateAiFailure(com.knot.backend.global.exception.LlmErrorCode code) {
        // given
        document();
        long id = start();
        doAnswer(invocation -> {
            cancellation.request(
                    workspace,
                    owner,
                    id
            );
            cancellation.complete(
                    workspace,
                    id
            );
            throw new com.knot.backend.global.exception.LlmException(code);
        }).when(planner)
                .plan(any());
        // when
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(
                planner,
                org.mockito.Mockito.times(1)
        )
                .plan(any());
    }

    @Test
    @DisplayName("적용 오류는 5초 뒤 한 번만 다시 실행하고 APPLYING 경계를 유지한다")
    void apply_success_storageRetry() {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        rejectUpdates();
        try {
            assertThatThrownBy(() -> apply.apply(execution)).isInstanceOf(DataAccessException.class);
            checkpoints.fail(
                    execution,
                    "STORAGE_ERROR",
                    true
            );
            assertThat(state(id)).isEqualTo("APPLYING");
            assertThatThrownBy(
                    () -> cancellation.request(
                            workspace,
                            owner,
                            id
                    )
            ).isInstanceOf(DocumentException.class);
            when(clock.instant()).thenReturn(NOW.plusSeconds(4));
            assertThat(
                    checkpoints.claim(
                            workspace,
                            id
                    )
            ).isEmpty();
        } finally {
            allowUpdates();
        }
        // when
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.queryForObject(
                        "SELECT retry_count FROM document_topic_operations WHERE id=?",
                        Integer.class,
                        id
                )
        ).isEqualTo(1);
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_recoveries",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("적용 재시도도 실패하면 부분 배정 없이 최종 실패하며 추가 실행하지 않는다")
    void apply_failure_storageRetryExhausted() {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        rejectUpdates();
        try {
            assertThatThrownBy(() -> apply.apply(execution)).isInstanceOf(DataAccessException.class);
            checkpoints.fail(
                    execution,
                    "STORAGE_ERROR",
                    true
            );
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            // when
            worker.execute(
                    workspace,
                    id
            );
            // then
            assertThat(state(id)).isEqualTo("FAILED");
            assertThat(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                            Integer.class
                    )
            ).isZero();
            assertThat(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM document_topic_recoveries",
                            Integer.class
                    )
            ).isZero();
            assertThat(
                    checkpoints.claim(
                            workspace,
                            id
                    )
            ).isEmpty();
        } finally {
            allowUpdates();
        }
    }

    @Test
    @DisplayName("적용 진입 뒤 전체 기한이 지나면 재인계 없이 실패하고 기존 배정을 유지한다")
    void claim_failure_applyingDeadline() {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        // when
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    @DisplayName("삭제된 Workspace의 접수된 취소도 내부 정리로 완료하며 외부 요청은404다")
    void complete_success_deletedWorkspace() {
        // given
        long id = start();
        cancellation.request(
                workspace,
                owner,
                id
        );
        jdbc.update(
                "UPDATE workspaces SET deleted_at=? WHERE id=?",
                Timestamp.from(NOW),
                workspace
        );
        // when
        cancellation.complete(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThatThrownBy(
                () -> cancellation.request(
                        workspace,
                        owner,
                        id
                )
        ).isInstanceOf(com.knot.backend.workspace.domain.WorkspaceException.class);
    }

    @Test
    @DisplayName("적용 진입의 commit이 먼저면 대기 중이던 취소 요청만409로 끝난다")
    void request_failure_concurrentApplyingWins() throws Exception {
        // given
        long id = start();
        DocumentTopicExecution execution = prepare(id);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> begin = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                checkpoints.beginApply(execution);
                locked.countDown();
                await(release);
            }));
            assertThat(
                    locked.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<?> cancel = executor.submit(
                    () -> cancellation.request(
                            workspace,
                            owner,
                            id
                    )
            );
            release.countDown();
            begin.get(
                    10,
                    TimeUnit.SECONDS
            );
            // when & then
            assertThatThrownBy(
                    () -> cancel.get(
                            10,
                            TimeUnit.SECONDS
                    )
            ).hasCauseInstanceOf(DocumentException.class);
        }
        assertThat(state(id)).isEqualTo("APPLYING");
    }

    @Test
    @DisplayName("동시에 취소를 눌러도 최초 접수 하나와 같은 완료 결과로 수렴한다")
    void request_success_concurrentDuplicates() throws Exception {
        // given
        long id = start();
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Instant> first = executor.submit(() -> {
                await(release);
                return cancellation.request(
                        workspace,
                        owner,
                        id
                )
                        .cancelRequestedAt();
            });
            Future<Instant> second = executor.submit(() -> {
                await(release);
                return cancellation.request(
                        workspace,
                        owner,
                        id
                )
                        .cancelRequestedAt();
            });
            // when
            release.countDown();
            // then
            assertThat(
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isEqualTo(
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
        cancellation.complete(
                workspace,
                id
        );
        assertThat(state(id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("실제 시계의 나노초 값도 DB 재조회 뒤 최초 취소 시각과 정확히 일치한다")
    void request_success_databaseTimestampPrecision() {
        // given
        long id = start();
        when(clock.instant()).thenReturn(NOW.plusNanos(123));
        // when
        Instant first = cancellation.request(
                workspace,
                owner,
                id
        )
                .cancelRequestedAt();
        Instant replay = cancellation.request(
                workspace,
                owner,
                id
        )
                .cancelRequestedAt();
        // then
        assertThat(replay).isEqualTo(first);
    }

    private void rejectUpdates() {
        jdbc.execute(
                "CREATE FUNCTION reject_cancellation_apply() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'apply failure'; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_cancellation_apply BEFORE UPDATE ON documents FOR EACH ROW EXECUTE FUNCTION reject_cancellation_apply()"
        );
    }

    private void allowUpdates() {
        jdbc.execute("DROP TRIGGER reject_cancellation_apply ON documents");
        jdbc.execute("DROP FUNCTION reject_cancellation_apply()");
    }

    private long start() {
        return start.start(
                workspace,
                owner,
                UUID.randomUUID()
        )
                .id();
    }

    private DocumentTopicExecution prepare(long id) {
        worker.execute(
                workspace,
                id
        );
        worker.execute(
                workspace,
                id
        );
        return checkpoints.claim(
                workspace,
                id
        )
                .orElseThrow();
    }

    private long document() {
        long recording = fixtures.saveRecording(
                workspace,
                owner,
                60000
        );
        long transcript = fixtures.saveTranscript(recording);
        long job = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        return fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                job,
                "전사 품질"
        );
    }

    private String state(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM document_topic_operations WHERE id=?",
                String.class,
                id
        );
    }

    private DocumentTopicPlan plan(DocumentTopicExecution execution) {
        if (execution.consolidate()) {
            return new DocumentTopicPlan(
                    Map.of(),
                    execution.categories()
                            .stream()
                            .collect(
                                    Collectors.toMap(
                                            c -> c.key(),
                                            c -> c.key()
                                    )
                            ),
                    Map.of(),
                    Map.of()
            );
        }
        return new DocumentTopicPlan(
                execution.targets()
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        t -> t.documentId(),
                                        t -> "e:" + category
                                )
                        ),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(
                    latch.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
