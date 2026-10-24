package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicCandidate;
import com.knot.backend.document.application.dto.result.DocumentTopicTarget;
import com.knot.backend.document.application.dto.result.DocumentTopicOperationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.time.Instant;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
class DocumentTopicReorganizationIntegrationTest {
    private static final Instant NOW = Instant.now()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    @Test
    @DisplayName("입력 한도를 넘은 배치는 줄여서 저장하고 남은 대상부터 이어간다")
    void execute_success_splitsOversizedBatch() {
        document("STT1");
        document("STT2");
        document("STT3");
        document("STT4");
        doAnswer(invocation -> {
            DocumentTopicExecution execution = invocation.getArgument(0);
            if (execution.targets()
                    .size() > 2) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
            return plan(execution);
        }).when(planner)
                .plan(any());
        DocumentTopicOperationResult result = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        long operation = id(result);
        worker.execute(
                workspace,
                operation
        );
        assertThat(
                jdbc.queryForObject(
                        "SELECT processed_count FROM document_topic_operations WHERE id=?",
                        Integer.class,
                        operation
                )
        ).isEqualTo(2);
        worker.execute(
                workspace,
                operation
        );
        worker.execute(
                workspace,
                operation
        );
        worker.execute(
                workspace,
                operation
        );
        assertThat(
                jdbc.queryForObject(
                        "SELECT status FROM document_topic_operations WHERE id=?",
                        String.class,
                        operation
                )
        ).isEqualTo("SUCCEEDED");
    }

    @Autowired
    private DocumentTopicReorganizationService start;
    @Autowired
    private DocumentTopicCheckpointService checkpoints;
    @Autowired
    private DocumentTopicApplyService apply;
    @Autowired
    private DocumentTopicWorker worker;
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
                        VALUES (?,'녹음·전사','녹음·전사','녹음 수집과 전사 품질','NORMAL',?) RETURNING id
                        """,
                Long.class,
                workspace,
                java.sql.Timestamp.from(NOW)
        );
        when(planner.plan(any())).thenAnswer(invocation -> plan(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("접수 대상은 고정되고 응답 유실 재전송은 같은 작업을 반환한다")
    void start_success_freezesAndReplays() {
        long first = document("STT");
        UUID request = UUID.randomUUID();
        DocumentTopicOperationResult result = start.start(
                workspace,
                owner,
                request
        );
        long next = document("작업 중 생성");
        DocumentTopicOperationResult replay = start.start(
                workspace,
                owner,
                request
        );
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operationId()).isEqualTo(result.operationId());
        assertThat(replay.targetCount()).isEqualTo(1);
        assertThat(
                jdbc.queryForList(
                        "SELECT document_id FROM document_topic_operation_targets",
                        Long.class
                )
        ).containsExactly(first);
        finish(result);
        assertThat(
                jdbc.queryForObject(
                        "SELECT category_id FROM documents WHERE id=?",
                        Long.class,
                        next
                )
        ).isNull();
        assertThat(
                jdbc.queryForObject(
                        "SELECT category_id FROM documents WHERE id=?",
                        Long.class,
                        first
                )
        ).isEqualTo(category);
    }

    @Test
    @DisplayName("현재 OWNER가 아니면 같은 requestId도 조회할 수 없다")
    void start_failure_notOwner() {
        document("STT");
        UUID request = UUID.randomUUID();
        start.start(
                workspace,
                owner,
                request
        );
        jdbc.update(
                "UPDATE workspace_members SET role='MEMBER' WHERE member_id=?",
                owner
        );
        assertThatThrownBy(
                () -> start.start(
                        workspace,
                        owner,
                        request
                )
        ).isInstanceOf(com.knot.backend.workspace.domain.WorkspaceException.class);
    }

    @Test
    @DisplayName("다른 요청의 활성 작업은 배타적이며 완료 요청 재전송은 먼저 해소한다")
    void start_success_replaysBeforeActiveConflict() {
        document("STT");
        UUID old = UUID.randomUUID();
        DocumentTopicOperationResult first = start.start(
                workspace,
                owner,
                old
        );
        finish(first);
        start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        assertThat(
                start.start(
                        workspace,
                        owner,
                        old
                )
                        .operationId()
        ).isEqualTo(first.operationId());
        assertThatThrownBy(
                () -> start.start(
                        workspace,
                        owner,
                        UUID.randomUUID()
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("동시에 같은 요청을 보내도 한 번만 접수한다")
    void start_success_concurrentSameRequest() throws Exception {
        document("STT");
        UUID request = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Callable<DocumentTopicOperationResult> task = () -> {
                ready.countDown();
                release.await(
                        5,
                        TimeUnit.SECONDS
                );
                return start.start(
                        workspace,
                        owner,
                        request
                );
            };
            Future<DocumentTopicOperationResult> first = threads.submit(task);
            Future<DocumentTopicOperationResult> second = threads.submit(task);
            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            release.countDown();
            assertThat(
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    )
                            .operationId()
            ).isEqualTo(
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    )
                            .operationId()
            );
        }
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operations",
                        Long.class
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("변경 성공만 복원 정보를 교체하며 변경이 없으면 이전 버전을 유지한다")
    void apply_success_noChangeKeepsRecoveryAndBody() {
        long doc = document("STT 개선");
        fixtures.target(
                doc,
                owner,
                NOW
        );
        DocumentTopicOperationResult first = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        finish(first);
        UUID recovery = jdbc.queryForObject(
                "SELECT version_id FROM document_topic_recoveries",
                UUID.class
        );
        long publicVersion = jdbc.queryForObject(
                "SELECT id FROM document_topic_recoveries",
                Long.class
        );
        jdbc.update(
                "UPDATE documents SET status='ARCHIVED', archived_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW),
                doc
        );
        DocumentTopicOperationResult second = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        finish(second);
        assertThat(
                jdbc.queryForObject(
                        "SELECT version_id FROM document_topic_recoveries",
                        UUID.class
                )
        ).isEqualTo(recovery);
        assertThat(
                jdbc.queryForObject(
                        "SELECT id FROM document_topic_recoveries",
                        Long.class
                )
        ).isEqualTo(publicVersion);
        assertThat(
                jdbc.queryForObject(
                        "SELECT changed_count FROM document_topic_operations WHERE operation_id=?",
                        Integer.class,
                        second.operationId()
                )
        ).isZero();
        assertThat(
                jdbc.queryForMap(
                        "SELECT topic,title,content,status FROM documents WHERE id=?",
                        doc
                )
        ).containsEntry(
                "topic",
                "STT 개선"
        )
                .containsEntry(
                        "title",
                        "문서 보관 정책"
                )
                .containsEntry(
                        "content",
                        "# 읽기 전용 본문"
                )
                .containsEntry(
                        "status",
                        "ARCHIVED"
                );
        assertThat(
                jdbc.queryForObject(
                        "SELECT confirmed_at FROM document_confirmations WHERE document_id=?",
                        java.sql.Timestamp.class,
                        doc
                )
        ).isEqualTo(java.sql.Timestamp.from(NOW));
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operation_targets",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("새 OWNER로 승계돼도 기존 작업은 Workspace 작업으로 완료된다")
    void execute_success_ownerTransferred() {
        document("STT");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        long next = fixtures.saveMember("새 OWNER");
        fixtures.join(
                workspace,
                next
        );
        jdbc.update(
                "UPDATE workspace_members SET role='MEMBER',left_at=? WHERE member_id=?",
                java.sql.Timestamp.from(NOW),
                owner
        );
        jdbc.update(
                "UPDATE workspace_members SET role='OWNER' WHERE member_id=?",
                next
        );
        finish(operation);
        assertThat(state(operation)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("삭제 Workspace에는 늦은 결과를 적용하지 않는다")
    void execute_failure_workspaceDeleted() {
        document("STT");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        jdbc.update(
                "UPDATE workspaces SET deleted_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW),
                workspace
        );
        worker.execute(
                workspace,
                id(operation)
        );
        assertThat(state(operation)).isEqualTo("FAILED");
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM documents WHERE category_id IS NOT NULL",
                        Integer.class
                )
        ).isZero();
    }

    @Test
    @DisplayName("만료 lease 인계 후 이전 응답을 저장하지 않는다")
    void complete_failure_staleWorker() {
        document("STT");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        DocumentTopicExecution first = checkpoints.claim(
                workspace,
                id(operation)
        )
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(91));
        DocumentTopicExecution next = checkpoints.claim(
                workspace,
                id(operation)
        )
                .orElseThrow();
        assertThatThrownBy(
                () -> checkpoints.complete(
                        first,
                        plan(first)
                )
        ).isInstanceOf(DocumentException.class);
        checkpoints.complete(
                next,
                plan(next)
        );
        assertThat(
                jdbc.queryForObject(
                        "SELECT processed_count FROM document_topic_operations",
                        Integer.class
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("큐 대기도 포함해 접수 30분 뒤에는 공개 배정을 보존한다")
    void execute_failure_deadline() {
        document("STT");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        worker.execute(
                workspace,
                id(operation)
        );
        assertThat(state(operation)).isEqualTo("FAILED");
        verifyNoInteractions(planner);
    }

    @Test
    @DisplayName("일시 오류는 5초 뒤 한 번 재시도하고 완료한 배치를 반복하지 않는다")
    void execute_success_transientRetry() {
        document("STT");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).doAnswer(invocation -> plan(invocation.getArgument(0)))
                .when(planner)
                .plan(any());
        worker.execute(
                workspace,
                id(operation)
        );
        worker.execute(
                workspace,
                id(operation)
        );
        verify(
                planner,
                times(1)
        ).plan(any());
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        finish(operation);
        assertThat(state(operation)).isEqualTo("SUCCEEDED");
        verify(
                planner,
                times(3)
        ).plan(any());
    }

    @Test
    @DisplayName("한 문서 UPDATE가 실패하면 앞서 바꾼 배정도 전부 롤백한다")
    void apply_failure_rollsBackAll() {
        document("STT 첫째");
        long second = document("STT 둘째");
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        worker.execute(
                workspace,
                id(operation)
        );
        worker.execute(
                workspace,
                id(operation)
        );
        jdbc.execute(
                "CREATE FUNCTION reject_topic_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id=" + second
                        + " THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_topic BEFORE UPDATE ON documents FOR EACH ROW EXECUTE FUNCTION reject_topic_update()"
        );
        try {
            DocumentTopicExecution execution = checkpoints.claim(
                    workspace,
                    id(operation)
            )
                    .orElseThrow();
            checkpoints.beginApply(execution);
            assertThatThrownBy(() -> apply.apply(execution))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
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
            assertThat(state(operation)).isEqualTo("APPLYING");
        } finally {
            jdbc.execute("DROP TRIGGER reject_topic ON documents");
            jdbc.execute("DROP FUNCTION reject_topic_update()");
        }
    }

    @Test
    @DisplayName("DB도 재정리와 복원의 활성 작업 동시 생성을 막는다")
    void persist_failure_activeRestoreConflict() {
        document("STT");
        start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        long sourceId = fixtures.saveCompletedTopicOperation(
                workspace,
                owner
        );
        assertThatThrownBy(
                () -> jdbc.update(
                        """
                                INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at,target_version_id,restoration_source_operation_id)
                                VALUES (?,?,?,?,'RESTORE','QUEUED',?,?,?,91,?)
                                """,
                        UUID.randomUUID(),
                        workspace,
                        owner,
                        UUID.randomUUID(),
                        java.sql.Timestamp.from(NOW),
                        java.sql.Timestamp.from(NOW.plusSeconds(1800)),
                        java.sql.Timestamp.from(NOW),
                        sourceId
                )
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("문서가 없으면 명세 오류를 반환하고 접수 데이터를 롤백한다")
    void start_failure_emptyWorkspace() {
        assertThatThrownBy(
                () -> start.start(
                        workspace,
                        owner,
                        UUID.randomUUID()
                )
        ).isInstanceOfSatisfying(
                DocumentException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                                .getCode()
                ).isEqualTo("TOPIC_REORGANIZATION_TARGET_EMPTY")
        );
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operations",
                        Long.class
                )
        ).isZero();
        verifyNoInteractions(planner);
    }

    @Test
    @DisplayName("대상 저장이 실패하면 접수 작업도 롤백한다")
    void start_failure_captureRollsBack() {
        document("STT");
        jdbc.execute(
                "CREATE FUNCTION reject_topic_capture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'capture failure'; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_capture BEFORE INSERT ON document_topic_operation_targets FOR EACH ROW EXECUTE FUNCTION reject_topic_capture()"
        );
        try {
            assertThatThrownBy(
                    () -> start.start(
                            workspace,
                            owner,
                            UUID.randomUUID()
                    )
            ).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM document_topic_operations",
                            Integer.class
                    )
            ).isZero();
            assertThat(
                    jdbc.queryForObject(
                            "SELECT count(*) FROM document_topic_operation_categories",
                            Integer.class
                    )
            ).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_capture ON document_topic_operation_targets");
            jdbc.execute("DROP FUNCTION reject_topic_capture()");
        }
    }

    @Test
    @DisplayName("반복 일시 오류가 나면 기존 배정과 복원 버전을 유지한다")
    void execute_failure_retryExhaustedKeepsRecovery() {
        document("STT");
        finish(
                start.start(
                        workspace,
                        owner,
                        UUID.randomUUID()
                )
        );
        UUID previous = jdbc.queryForObject(
                "SELECT version_id FROM document_topic_recoveries",
                UUID.class
        );
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        doThrow(new LlmException(LlmErrorCode.LLM_UNAVAILABLE)).when(planner)
                .plan(any());
        worker.execute(
                workspace,
                id(operation)
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspace,
                id(operation)
        );
        assertThat(state(operation)).isEqualTo("FAILED");
        assertThat(
                jdbc.queryForObject(
                        "SELECT version_id FROM document_topic_recoveries",
                        UUID.class
                )
        ).isEqualTo(previous);
        assertThat(
                jdbc.queryForObject(
                        "SELECT count(*) FROM document_topic_operation_targets WHERE operation_id=?",
                        Integer.class,
                        id(operation)
                )
        ).isZero();
    }

    @Test
    @DisplayName("복원에 사용한 requestId를 재정리에 재사용하면409로 거절한다")
    void start_failure_requestKindConflict() {
        UUID request = UUID.randomUUID();
        long sourceId = fixtures.saveCompletedTopicOperation(
                workspace,
                owner
        );
        jdbc.update(
                """
                        INSERT INTO document_topic_operations(operation_id,workspace_id,requester_id,request_id,kind,status,created_at,deadline_at,next_attempt_at,finished_at,target_version_id,restoration_source_operation_id)
                        VALUES (?,?,?,?,'RESTORE','SUCCEEDED',?,?,?,?,91,?)
                        """,
                UUID.randomUUID(),
                workspace,
                owner,
                request,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(1800)),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                sourceId
        );
        assertThatThrownBy(
                () -> start.start(
                        workspace,
                        owner,
                        request
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("5천 문서 접수와 전체 적용 시간을 모의 모델·실제 PostgreSQL에서 측정한다")
    void execute_success_fiveThousandDocuments() {
        int count = 5000;
        long recording = fixtures.saveRecording(
                workspace,
                owner,
                60000
        );
        long transcript = fixtures.saveTranscript(recording);
        long seedJob = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        long batch = jdbc.queryForObject(
                "SELECT batch_id FROM document_generation_jobs WHERE id=?",
                Long.class,
                seedJob
        );
        jdbc.update(
                "INSERT INTO document_generation_batch_topics(batch_id,position,topic) SELECT ?,n,'공수 측정 '||n FROM generate_series(1,?) n",
                batch,
                count
        );
        jdbc.update(
                """
                        INSERT INTO document_generation_jobs(batch_id,stage,topic,transcript_id,status,created_at,updated_at)
                        SELECT ?,'GENERATION',topic,?,'SUCCEEDED',?,? FROM document_generation_batch_topics WHERE batch_id=? AND position>0
                        """,
                batch,
                transcript,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                batch
        );
        jdbc.update(
                """
                        INSERT INTO documents(workspace_id,recording_session_id,source_transcript_id,document_generation_job_id,topic,title,content,status,created_at)
                        SELECT ?,?,?,id,topic,'전사 품질 검증','전사 품질을 개선한다','DRAFT',? FROM document_generation_jobs WHERE batch_id=? AND id<>?
                        """,
                workspace,
                recording,
                transcript,
                java.sql.Timestamp.from(NOW),
                batch,
                seedJob
        );
        long started = System.nanoTime();
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        long accepted = System.nanoTime();
        finish(operation);
        long completed = System.nanoTime();
        assertThat(operation.targetCount()).isEqualTo(count);
        assertThat(
                jdbc.queryForObject(
                        "SELECT changed_count FROM document_topic_operations",
                        Integer.class
                )
        ).isEqualTo(count);
        System.out.printf(
                "TOPIC_BENCHMARK documents=%d acceptMs=%d processingMs=%d model=MOCK%n",
                count,
                TimeUnit.NANOSECONDS.toMillis(accepted - started),
                TimeUnit.NANOSECONDS.toMillis(completed - accepted)
        );
    }

    @Test
    @DisplayName("퇴역 주제를 참조하는 작업 중 신규 문서는 원래 폴더를 유지한다")
    void apply_success_preservesNewDocumentInRetiredCategory() {
        long old = jdbc.queryForObject(
                """
                        INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at)
                        VALUES (?,'음성 처리','음성 처리','녹음 수집과 전사 품질','NORMAL',?) RETURNING id
                        """,
                Long.class,
                workspace,
                java.sql.Timestamp.from(NOW)
        );
        long first = document("STT");
        jdbc.update(
                "UPDATE documents SET category_id=?,grouping_scheme='BROAD',assignment_source='AI_REUSE',assignment_rule_version='v1' WHERE id=?",
                old,
                first
        );
        DocumentTopicOperationResult operation = start.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        long next = document("새 STT");
        jdbc.update(
                "UPDATE documents SET category_id=?,grouping_scheme='BROAD',assignment_source='AI_REUSE',assignment_rule_version='v1' WHERE id=?",
                old,
                next
        );
        finish(operation);
        assertThat(
                jdbc.queryForObject(
                        "SELECT active_for_assignment FROM document_categories WHERE id=?",
                        Boolean.class,
                        old
                )
        ).isFalse();
        assertThat(
                jdbc.queryForObject(
                        "SELECT category_id FROM documents WHERE id=?",
                        Long.class,
                        next
                )
        ).isEqualTo(old);
        assertThat(
                jdbc.queryForObject(
                        "SELECT c.name FROM documents d JOIN document_categories c ON d.category_id=c.id WHERE d.id=?",
                        String.class,
                        next
                )
        ).isEqualTo("음성 처리");
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

    private DocumentTopicPlan plan(DocumentTopicExecution execution) {
        if (execution.consolidate()) {
            Map<String, String> mapping = execution.categories()
                    .stream()
                    .collect(
                            Collectors.toMap(
                                    DocumentTopicCandidate::key,
                                    DocumentTopicCandidate::key
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
                                d -> "e:" + category
                        )
                );
        return new DocumentTopicPlan(
                assignments,
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private long id(DocumentTopicOperationResult result) {
        return jdbc.queryForObject(
                "SELECT id FROM document_topic_operations WHERE operation_id=?",
                Long.class,
                result.operationId()
        );
    }

    private String state(DocumentTopicOperationResult result) {
        return jdbc.queryForObject(
                "SELECT status FROM document_topic_operations WHERE operation_id=?",
                String.class,
                result.operationId()
        );
    }

    private void finish(DocumentTopicOperationResult result) {
        for (int step = 0; step < result.targetCount() / 25 + 5; step++) {
            if ("SUCCEEDED".equals(state(result))) {
                return;
            }
            assertThat(state(result)).isNotEqualTo("FAILED");
            worker.execute(
                    workspace,
                    id(result)
            );
        }
        assertThat(state(result)).isEqualTo("SUCCEEDED");
    }
}
