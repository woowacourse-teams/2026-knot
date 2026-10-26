package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false",
        "knot.document-topic-reorganization.enabled=true", "knot.document-topic-reorganization.batch-size=2",
        "knot.document-topic-reorganization.parallelism=4"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentTopicParallelIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Autowired
    private DocumentTopicReorganizationService start;
    @Autowired
    private DocumentTopicCheckpointService checkpoints;
    @Autowired
    private DocumentTopicBatchCheckpointService batchCheckpoints;
    @Autowired
    private DocumentTopicWorker worker;
    @Autowired
    private DocumentTopicCancellationService cancellations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient client;
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
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE members,workspaces RESTART IDENTITY CASCADE");
        fixtures = new DocumentFixtures(client);
        owner = fixtures.saveMember("흑곰");
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
                """
                        INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at)
                        VALUES (?,'녹음·전사','녹음·전사','음성 처리','NORMAL',?) RETURNING id
                        """,
                Long.class,
                workspace,
                Timestamp.from(NOW)
        );
        when(planner.plan(any())).thenAnswer(invocation -> plan(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("네 배치를 동시에 처리하고 완료 순서와 무관하게 한 번씩만 저장한다")
    void execute_success_fourConcurrentBatches() throws Exception {
        // given
        for (int index = 0; index < 10; index++) {
            document("STT " + index);
        }
        long operation = start();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Long> observed = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            int count = active.incrementAndGet();
            peak.accumulateAndGet(
                    count,
                    Math::max
            );
            entered.countDown();
            try {
                assertThat(
                        release.await(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                for (DocumentTopicTarget target : execution.targets()) {
                    assertThat(observed.add(target.documentId())).isTrue();
                }
                return plan(execution);
            } finally {
                active.decrementAndGet();
            }
        }).when(planner)
                .plan(any());
        // when
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(
                    () -> worker.execute(
                            workspace,
                            operation
                    )
            );
            try {
                assertThat(
                        entered.await(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                assertThat(publicCount()).isZero();
            } finally {
                release.countDown();
            }
            running.get(
                    15,
                    TimeUnit.SECONDS
            );
        }
        // then
        assertThat(peak.get()).isEqualTo(4);
        assertThat(observed).hasSize(10);
        assertThat(processed(operation)).isEqualTo(10);
        assertThat(publicCount()).isZero();
        finish(operation);
        assertThat(publicCount()).isEqualTo(10);
        assertThat(state(operation)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("동시 배치가 같은 n:1을 반환해도 서로 다른 후보로 보존한다")
    void execute_success_scopesNewCategoryKeys() {
        // given
        document("채용");
        document("채용");
        document("철학");
        document("철학");
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            if (execution.consolidate()) {
                return plan(execution);
            }
            DocumentTopicTarget first = execution.targets()
                    .getFirst();
            Map<Long, String> assignments = execution.targets()
                    .stream()
                    .collect(
                            Collectors.toMap(
                                    DocumentTopicTarget::documentId,
                                    target -> "n:1"
                            )
                    );
            return new DocumentTopicPlan(
                    assignments,
                    Map.of(),
                    Map.of(
                            "n:1",
                            first.agendaTopic()
                    ),
                    Map.of(
                            "n:1",
                            first.agendaTopic() + " 영역"
                    )
            );
        }).when(planner)
                .plan(any());
        long operation = start();
        // when
        worker.execute(
                workspace,
                operation
        );
        // then
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(DISTINCT after_key) FROM document_topic_operation_targets WHERE operation_id=?",
                        Integer.class,
                        operation
                )
        ).isEqualTo(2);
        finish(operation);
        assertThat(
                jdbc.queryForList(
                        "SELECT DISTINCT c.name FROM documents d JOIN document_categories c ON c.id=d.category_id ORDER BY c.name",
                        String.class
                )
        ).containsExactly(
                "채용",
                "철학"
        );
    }

    @Test
    @DisplayName("부분 체크포인트 후 재시작하면 완료 문서는 호출하지 않고 이전 토큰 응답은 버린다")
    void execute_success_resumesOnlyPendingAfterRestart() {
        // given
        for (int index = 0; index < 6; index++) {
            document("STT " + index);
        }
        long operation = start();
        DocumentTopicExecution stale = checkpoints.claim(
                workspace,
                operation
        )
                .orElseThrow();
        batchCheckpoints.completeBatch(
                stale,
                plan(stale)
        );
        Set<Long> completed = stale.targets()
                .stream()
                .map(DocumentTopicTarget::documentId)
                .collect(Collectors.toSet());
        when(clock.instant()).thenReturn(NOW.plusSeconds(181));
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            assertThat(execution.targets()).noneMatch(target -> completed.contains(target.documentId()));
            return plan(execution);
        }).when(planner)
                .plan(any());
        // when
        worker.execute(
                workspace,
                operation
        );
        // then
        assertThat(processed(operation)).isEqualTo(6);
        assertThat(
                batchCheckpoints.completeBatch(
                        stale,
                        plan(stale)
                )
        ).isFalse();
        finish(operation);
        assertThat(state(operation)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("배치 재시도 횟수는 다른 배치 성공과 새 claim 뒤에도 유지한다")
    void retryBatch_failure_preservesBudgetAcrossClaims() {
        // given
        document("STT 1");
        document("STT 2");
        document("STT 3");
        long operation = start();
        DocumentTopicExecution first = checkpoints.claim(
                workspace,
                operation
        )
                .orElseThrow();
        // when
        assertThat(
                batchCheckpoints.retryBatch(
                        first,
                        "LLM_TIMEOUT"
                )
        ).isTrue();
        DocumentTopicExecution other = batchCheckpoints.nextBatch(
                first,
                Set.of()
        )
                .orElseThrow();
        batchCheckpoints.completeBatch(
                other,
                plan(other)
        );
        batchCheckpoints.finishBatches(first);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        DocumentTopicExecution retry = checkpoints.claim(
                workspace,
                operation
        )
                .orElseThrow();
        batchCheckpoints.retryBatch(
                retry,
                "LLM_TIMEOUT"
        );
        // then
        assertThat(state(operation)).isEqualTo("FAILED");
        assertThat(publicCount()).isZero();
    }

    @Test
    @DisplayName("네 모델 응답 대기 중 취소하면 늦은 응답 모두 공개 배정과 진행 수를 바꾸지 못한다")
    void execute_success_discardsFourLateResponsesAfterCancellation() throws Exception {
        // given
        for (int index = 0; index < 8; index++) {
            document("STT " + index);
        }
        long operation = start();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(4);
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            entered.countDown();
            try {
                release.await(
                        10,
                        TimeUnit.SECONDS
                );
                return plan(execution);
            } finally {
                returned.countDown();
            }
        }).when(planner)
                .plan(any());
        // when
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(
                    () -> worker.execute(
                            workspace,
                            operation
                    )
            );
            try {
                assertThat(
                        entered.await(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                cancellations.request(
                        workspace,
                        owner,
                        operation
                );
                cancellations.complete(
                        workspace,
                        operation
                );
            } finally {
                release.countDown();
            }
            running.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(
                    returned.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
        }
        // then
        assertThat(state(operation)).isEqualTo("CANCELLED");
        assertThat(processed(operation)).isZero();
        assertThat(publicCount()).isZero();
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operation_targets",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    @DisplayName("공유 마감 이후 도착한 병렬 결과는 적용하지 않고 마감 실패로 종료한다")
    void execute_failure_deadlineDiscardsParallelResults() throws Exception {
        // given
        for (int index = 0; index < 8; index++) {
            document("STT " + index);
        }
        long operation = start();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            entered.countDown();
            release.await(
                    10,
                    TimeUnit.SECONDS
            );
            return plan(execution);
        }).when(planner)
                .plan(any());
        // when
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(
                    () -> worker.execute(
                            workspace,
                            operation
                    )
            );
            try {
                assertThat(
                        entered.await(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
            } finally {
                release.countDown();
            }
            running.get(
                    10,
                    TimeUnit.SECONDS
            );
        }
        worker.execute(
                workspace,
                operation
        );
        // then
        assertThat(state(operation)).isEqualTo("FAILED");
        assertThat(publicCount()).isZero();
        assertThat(processed(operation)).isZero();
    }

    private long start() {
        DocumentTopicOperationResult accepted = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        return jdbc.queryForObject(
                "SELECT id FROM document_topic_operations WHERE operation_id=?",
                Long.class,
                accepted.operationId()
        );
    }

    @Test
    @DisplayName("출력 토큰 한도 초과도 배치를 나누어 누락 없이 끝낸다")
    void execute_success_splitsOutputLimitedBatches() {
        // given
        for (int index = 0; index < 8; index++) {
            document("STT " + index);
        }
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            if (!execution.consolidate() && execution.targets()
                    .size() > 1) {
                throw new LlmException(LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED);
            }
            return plan(execution);
        }).when(planner)
                .plan(any());
        long operation = start();
        // when
        worker.execute(
                workspace,
                operation
        );
        finish(operation);
        // then
        assertThat(state(operation)).isEqualTo("SUCCEEDED");
        assertThat(publicCount()).isEqualTo(8);
    }

    private void finish(long operation) {
        worker.execute(
                workspace,
                operation
        );
        worker.execute(
                workspace,
                operation
        );
    }

    private long document(String topic) {
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
                topic
        );
    }

    private int processed(long operation) {
        return jdbc.queryForObject(
                "SELECT processed_count FROM document_topic_operations WHERE id=?",
                Integer.class,
                operation
        );
    }

    private int publicCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                Integer.class
        );
    }

    private String state(long operation) {
        return jdbc.queryForObject(
                "SELECT status FROM document_topic_operations WHERE id=?",
                String.class,
                operation
        );
    }

    private DocumentTopicPlan plan(DocumentTopicExecution execution) {
        if (execution.consolidate()) {
            Map<String, String> mapping = execution.categories()
                    .stream()
                    .collect(
                            Collectors.toMap(
                                    candidate -> candidate.key(),
                                    candidate -> candidate.key()
                            )
                    );
            return new DocumentTopicPlan(
                    Map.of(),
                    mapping,
                    Map.of(),
                    Map.of()
            );
        }
        Map<Long, String> assignments = execution.targets()
                .stream()
                .collect(
                        Collectors.toMap(
                                DocumentTopicTarget::documentId,
                                target -> "e:" + category
                        )
                );
        return new DocumentTopicPlan(
                assignments,
                Map.of(),
                Map.of(),
                Map.of()
        );
    }
}
