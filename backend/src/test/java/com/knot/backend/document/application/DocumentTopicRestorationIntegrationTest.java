package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicExecution;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationResult;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentTopicPlan;
import com.knot.backend.document.infrastructure.DocumentTopicScheduler;
import com.knot.backend.document.infrastructure.llm.LlmDocumentTopicPlanner;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.junit.jupiter.params.provider.ValueSource;
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
class DocumentTopicRestorationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Autowired
    private DocumentTopicRestorationService service;
    @Autowired
    private DocumentTopicRestorationCheckpointService checkpoints;
    @Autowired
    private DocumentTopicRestorationApplyService apply;
    @Autowired
    private DocumentTopicRestorationWorker worker;
    @Autowired
    private DocumentTopicReorganizationService reorganizations;
    @Autowired
    private DocumentTopicWorker reorganizationWorker;
    @Autowired
    private DocumentTopicCancellationService cancellations;
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
    private long merged;
    private long original;
    private long fallback;
    private long source;
    private long version;
    private long legacyDocument;
    private long broadDocument;
    private long fallbackDocument;
    private long unchangedDocument;
    private long newDocument;

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
        merged = category(
                "녹음·전사",
                "NORMAL"
        );
        original = category(
                "음성 처리",
                "NORMAL"
        );
        fallback = category(
                "기타",
                "FALLBACK"
        );
        legacyDocument = document();
        broadDocument = document();
        fallbackDocument = document();
        unchangedDocument = document();
        assign(
                broadDocument,
                original,
                "AI_NEW",
                "old-rule"
        );
        assign(
                fallbackDocument,
                fallback,
                "FALLBACK",
                "fallback-v1"
        );
        assign(
                unchangedDocument,
                merged,
                "AI_REUSE",
                "unchanged-v1"
        );
        when(planner.plan(any())).thenAnswer(invocation -> plan(invocation.getArgument(0)));
        source = reorganizations.start(
                workspace,
                owner,
                UUID.randomUUID()
        )
                .id();
        for (int i = 0; i < 3; i++) {
            reorganizationWorker.execute(
                    workspace,
                    source
            );
        }
        assertThat(state(source)).isEqualTo("SUCCEEDED");
        version = jdbc.queryForObject(
                "SELECT id FROM document_topic_recoveries WHERE workspace_id=?",
                Long.class,
                workspace
        );
        newDocument = document();
        assign(
                newDocument,
                merged,
                "AI_REUSE",
                "new-rule"
        );
        clearInvocations(planner);
    }

    @Test
    @DisplayName("저장된 변경 문서만 복원하며 LEGACY·기타·원래 메타데이터와 이후 문서를 유지한다")
    void restore_success_selectiveAssignments() {
        // given
        long id = start();
        jdbc.update(
                "UPDATE documents SET status='ARCHIVED',archived_at=? WHERE id=?",
                Timestamp.from(NOW),
                broadDocument
        );
        fixtures.target(
                broadDocument,
                owner,
                NOW
        );
        Map<String, Object> confirmationBefore = jdbc.queryForMap(
                "SELECT * FROM document_confirmations WHERE document_id=?",
                broadDocument
        );
        Map<String, Object> documentBefore = row(broadDocument);
        // when
        finish(id);
        // then
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.queryForMap(
                        "SELECT * FROM document_confirmations WHERE document_id=?",
                        broadDocument
                )
        ).isEqualTo(confirmationBefore);
        Map<String, Object> documentAfter = row(broadDocument);
        for (String column : List.of(
                "category_id",
                "grouping_scheme",
                "assignment_source",
                "assignment_rule_version"
        )) {
            documentBefore.remove(column);
            documentAfter.remove(column);
        }
        assertThat(documentAfter).isEqualTo(documentBefore);
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                null
        )
                .containsEntry(
                        "grouping_scheme",
                        "LEGACY"
                );
        assertThat(row(broadDocument)).containsEntry(
                "category_id",
                original
        )
                .containsEntry(
                        "assignment_source",
                        "AI_NEW"
                )
                .containsEntry(
                        "assignment_rule_version",
                        "old-rule"
                )
                .containsEntry(
                        "status",
                        "ARCHIVED"
                );
        assertThat(row(fallbackDocument)).containsEntry(
                "category_id",
                fallback
        )
                .containsEntry(
                        "assignment_source",
                        "FALLBACK"
                );
        assertThat(row(unchangedDocument)).containsEntry(
                "category_id",
                merged
        )
                .containsEntry(
                        "assignment_rule_version",
                        "unchanged-v1"
                );
        assertThat(row(newDocument)).containsEntry(
                "category_id",
                merged
        )
                .containsEntry(
                        "assignment_rule_version",
                        "new-rule"
                );
        assertThat(row(broadDocument)).containsEntry(
                "content",
                "# 읽기 전용 본문"
        )
                .containsEntry(
                        "topic",
                        "전사 품질"
                );
        assertThat(recoveryCount()).isZero();
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .canRestore()
        ).isFalse();
        assertThat(count("document_topic_restoration_targets")).isZero();
        assertThat(count("document_topic_operation_targets")).isZero();
        assertThat(count("document_topic_operations")).isEqualTo(2);
        verifyNoInteractions(planner);
    }

    @Test
    @DisplayName("성공으로 복원 정보가 소진된 후에도 같은 요청은 기존 버전과 최초 시각을 반환한다")
    void start_success_replayAfterConsumption() {
        // given
        UUID request = UUID.randomUUID();
        when(clock.instant()).thenReturn(NOW.plusNanos(123));
        DocumentTopicRestorationResult first = service.start(
                workspace,
                owner,
                request,
                version
        );
        finish(first.operationId());
        reorganizations.start(
                workspace,
                owner,
                UUID.randomUUID()
        );
        // when
        DocumentTopicRestorationResult replay = service.start(
                workspace,
                owner,
                request,
                version
        );
        // then
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(replay.targetVersionId()).isEqualTo(version);
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(
                replay.status()
                        .name()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .operation()
                        .type()
        ).isEqualTo("REORGANIZATION");
    }

    @Test
    @DisplayName("이전 요청의 versionId 재사용과 다른 종류 requestId는 충돌한다")
    void start_failure_requestIdConflict() {
        // given
        UUID request = UUID.randomUUID();
        service.start(
                workspace,
                owner,
                request,
                version
        );
        // when & then
        assertThatThrownBy(
                () -> service.start(
                        workspace,
                        owner,
                        request,
                        version + 1
                )
        ).isInstanceOf(DocumentException.class)
                .hasMessageContaining("같은 요청");
        assertThatThrownBy(
                () -> reorganizations.start(
                        workspace,
                        owner,
                        request
                )
        ).isInstanceOf(DocumentException.class)
                .hasMessageContaining("같은 요청");
    }

    @Test
    @DisplayName("활성 작업·오래된 버전·소진은 각기 다른 오류이며 새 작업을 만들지 않는다")
    void start_failure_conflicts() {
        // when & then
        assertThatThrownBy(
                () -> service.start(
                        workspace,
                        owner,
                        UUID.randomUUID(),
                        version + 1
                )
        ).hasMessageContaining("복원 대상이 변경");
        long id = start();
        assertThatThrownBy(
                () -> service.start(
                        workspace,
                        owner,
                        UUID.randomUUID(),
                        version
                )
        ).hasMessageContaining("진행 중");
        finish(id);
        assertThatThrownBy(
                () -> service.start(
                        workspace,
                        owner,
                        UUID.randomUUID(),
                        version
                )
        ).hasMessageContaining("기록이 없습니다");
        assertThat(count("document_topic_operations")).isEqualTo(2);
    }

    @Test
    @DisplayName("복원 대상은 변경된 세 문서로 고정하며 생성 시각이 같은 새 문서를 포함하지 않는다")
    void start_success_snapshotChangedOnly() {
        // given
        long id = start();
        // when
        List<Long> ids = jdbc.queryForList(
                "SELECT document_id FROM document_topic_restoration_targets WHERE operation_id=? ORDER BY document_id",
                Long.class,
                id
        );
        // then
        assertThat(ids).containsExactly(
                legacyDocument,
                broadDocument,
                fallbackDocument
        );
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .operation()
                        .targetVersionId()
        ).isEqualTo(version);
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .operation()
                        .targetDocumentCount()
        ).isEqualTo(3);
    }

    @Test
    @DisplayName("준비 중 취소는 복원 기회를 보존하고 늦은 실행자와 중복 취소를 안전하게 처리한다")
    void cancel_success_beforeApplying() {
        // given
        long id = start();
        DocumentTopicRestorationExecution execution = claim(id);
        // when
        cancellations.request(
                workspace,
                owner,
                id
        );
        assertThat(checkpoints.prepare(execution)).isFalse();
        assertThat(checkpoints.beginApply(execution)).isFalse();
        cancellations.complete(
                workspace,
                id
        );
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                merged
        );
        assertThat(count("document_topic_restoration_targets")).isZero();
        assertThat(count("document_topic_operation_targets")).isEqualTo(4);
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .canRestore()
        ).isTrue();
        assertThat(
                cancellations.request(
                        workspace,
                        owner,
                        id
                )
                        .status()
                        .name()
        ).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("APPLYING이 먼저 확정되면 취소를409로 거절한다")
    void cancel_failure_applyingCommitted() {
        // given
        long id = start();
        DocumentTopicRestorationExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        // when & then
        assertThatThrownBy(
                () -> cancellations.request(
                        workspace,
                        owner,
                        id
                )
        ).hasMessageContaining("취소할 수 없습니다");
        apply.apply(execution);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("준비 재시작은 완료 수를 중복 집계하지 않고 늦은 token을 거절한다")
    void prepare_success_restartFromCheckpoint() {
        // given
        long id = start();
        DocumentTopicRestorationExecution stale = claim(id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(91));
        DocumentTopicRestorationExecution resumed = claim(id);
        // when
        assertThat(checkpoints.prepare(stale)).isFalse();
        checkpoints.prepare(resumed);
        // then
        assertThat(
                status.find(
                        workspace,
                        owner
                )
                        .operation()
                        .processedDocumentCount()
        ).isEqualTo(3);
        finish(id);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("APPLYING 중단 후 lease가 지나면 같은 자료로 복원하고 이전 token은 적용하지 못한다")
    void apply_success_reclaimsAfterRestart() {
        // given
        long id = start();
        DocumentTopicRestorationExecution stale = prepare(id);
        checkpoints.beginApply(stale);
        when(clock.instant()).thenReturn(NOW.plusSeconds(91));
        // when
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> apply.apply(stale)).isInstanceOf(DocumentException.class);
        assertThat(recoveryCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"documents", "document_topic_recoveries", "document_topic_restoration_targets",
            "document_topic_operation_targets"})
    @DisplayName("문서·소진·정리 중 저장 오류는 배정·성공·소진을 함께 롤백하고5초 뒤 복구한다")
    void apply_success_recoversStorageFailure(String table) {
        // given
        long id = start();
        DocumentTopicRestorationExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        reject(table);
        try {
            // when
            worker.execute(
                    workspace,
                    id
            );
            assertThatThrownBy(() -> apply.apply(execution)).isInstanceOf(DataAccessException.class);
            checkpoints.fail(
                    execution,
                    "STORAGE_ERROR",
                    true
            );
            // then
            assertThat(state(id)).isEqualTo("APPLYING");
            assertThat(row(legacyDocument)).containsEntry(
                    "category_id",
                    merged
            );
            assertThat(recoveryCount()).isEqualTo(1);
            assertThat(
                    status.find(
                            workspace,
                            owner
                    )
                            .operation()
                            .processedDocumentCount()
            ).isEqualTo(3);
        } finally {
            allow(table);
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(4));
        worker.execute(
                workspace,
                id
        );
        assertThat(state(id)).isEqualTo("APPLYING");
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspace,
                id
        );
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("두 번째 적용 저장 실패는 FAILED로 종료하고 원본 분류와 복원 기회를 유지한다")
    void apply_failure_exhaustedRetry() {
        // given
        long id = start();
        DocumentTopicRestorationExecution execution = prepare(id);
        checkpoints.beginApply(execution);
        reject("documents");
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
            assertThat(row(legacyDocument)).containsEntry(
                    "category_id",
                    merged
            );
            assertThat(recoveryCount()).isEqualTo(1);
            assertThat(
                    status.find(
                            workspace,
                            owner
                    )
                            .operation()
                            .failure()
                            .code()
            ).isEqualTo("TOPIC_RESTORATION_FAILED");
            assertThat(
                    status.find(
                            workspace,
                            owner
                    )
                            .canRestore()
            ).isTrue();
        } finally {
            allow("documents");
        }
    }

    @Test
    @DisplayName("최초30분 기한은 재시작으로 연장하지 않고 실패한다")
    void restore_failure_deadlineExceeded() {
        // given
        long id = start();
        claim(id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        // when
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                merged
        );
    }

    @Test
    @DisplayName("접수 이후 배정이 외부에서 바뀌면 부분 복원 없이 중단한다")
    void restore_failure_expectedAssignmentMismatch() {
        // given
        long id = start();
        assign(
                broadDocument,
                merged,
                "RULE_REUSE",
                "tampered"
        );
        // when
        finish(id);
        // then
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                merged
        );
        assertThat(recoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("후속 문서가 참조하는 새 주제와 후속 무관 주제의 가용성을 유지한다")
    void restore_success_preservesNewReferences() {
        // given
        jdbc.update(
                "UPDATE document_categories SET active_for_assignment=FALSE WHERE id=?",
                merged
        );
        long unrelated = category(
                "배포",
                "NORMAL"
        );
        long id = start();
        long during = document();
        assign(
                during,
                merged,
                "RULE_REUSE",
                "during-rule"
        );
        // when
        finish(id);
        // then
        assertThat(availability(original)).isTrue();
        assertThat(availability(merged)).isTrue();
        assertThat(availability(unrelated)).isTrue();
        assertThat(row(during)).containsEntry(
                "category_id",
                merged
        );
    }

    @Test
    @DisplayName("OWNER가 바뀌어도 작업은 계속하고 이전 OWNER는 재요청할 수 없다")
    void restore_success_ownerTransfer() {
        // given
        UUID request = UUID.randomUUID();
        long id = service.start(
                workspace,
                owner,
                request,
                version
        )
                .operationId();
        fixtures.leave(
                workspace,
                owner
        );
        // when
        finish(id);
        // then
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThatThrownBy(
                () -> service.start(
                        workspace,
                        owner,
                        request,
                        version
                )
        ).hasMessageContaining("접근할 수 없습니다");
    }

    @Test
    @DisplayName("Workspace 삭제는 복원을 공개하지 않고 실패로 끝낸다")
    void restore_failure_workspaceDeleted() {
        // given
        long id = start();
        jdbc.update(
                "UPDATE workspaces SET deleted_at=? WHERE id=?",
                Timestamp.from(NOW),
                workspace
        );
        // when
        worker.execute(
                workspace,
                id
        );
        // then
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                merged
        );
    }

    @Test
    @DisplayName("같은 requestId 동시 접수는 동일 작업과 한 벌의 고정 대상만 만든다")
    void start_success_concurrentReplay() throws Exception {
        // given
        UUID request = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DocumentTopicRestorationResult> first = executor.submit(() -> {
                gate.await(
                        10,
                        TimeUnit.SECONDS
                );
                return service.start(
                        workspace,
                        owner,
                        request,
                        version
                );
            });
            Future<DocumentTopicRestorationResult> second = executor.submit(() -> {
                gate.await(
                        10,
                        TimeUnit.SECONDS
                );
                return service.start(
                        workspace,
                        owner,
                        request,
                        version
                );
            });
            // when
            gate.countDown();
            DocumentTopicRestorationResult a = first.get(
                    10,
                    TimeUnit.SECONDS
            );
            DocumentTopicRestorationResult b = second.get(
                    10,
                    TimeUnit.SECONDS
            );
            // then
            assertThat(a.operationId()).isEqualTo(b.operationId());
            assertThat(a.replayed()).isNotEqualTo(b.replayed());
            assertThat(count("document_topic_restoration_targets")).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("복원 대상 저장 실패는 접수 작업도 롤백하며 같은 요청으로 다시 접수할 수 있다")
    void start_success_retriesCaptureFailure() {
        // given
        UUID request = UUID.randomUUID();
        jdbc.execute(
                "CREATE FUNCTION reject_restoration_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'capture failure'; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_restoration_insert BEFORE INSERT ON document_topic_restoration_targets FOR EACH ROW EXECUTE FUNCTION reject_restoration_insert()"
        );
        try {
            // when & then
            assertThatThrownBy(
                    () -> service.start(
                            workspace,
                            owner,
                            request,
                            version
                    )
            ).isInstanceOf(DataAccessException.class);
            assertThat(count("document_topic_operations")).isEqualTo(1);
            assertThat(recoveryCount()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER reject_restoration_insert ON document_topic_restoration_targets");
            jdbc.execute("DROP FUNCTION reject_restoration_insert()");
        }
        assertThat(
                service.start(
                        workspace,
                        owner,
                        request,
                        version
                )
                        .replayed()
        ).isFalse();
    }

    @Test
    @DisplayName("취소 자료 정리 실패는 CANCELLED와 삭제를 함께 롤백하고 다음 처리에서 완료한다")
    void cancel_success_cleanupRetry() {
        // given
        long id = start();
        cancellations.request(
                workspace,
                owner,
                id
        );
        reject("document_topic_restoration_targets");
        try {
            // when & then
            assertThatThrownBy(
                    () -> cancellations.complete(
                            workspace,
                            id
                    )
            ).isInstanceOf(DataAccessException.class);
            assertThat(state(id)).isEqualTo("CANCEL_REQUESTED");
            assertThat(count("document_topic_restoration_targets")).isEqualTo(3);
            assertThat(recoveryCount()).isEqualTo(1);
        } finally {
            allow("document_topic_restoration_targets");
        }
        cancellations.complete(
                workspace,
                id
        );
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(recoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("접수 당시 원본 변경 수가 실제 기록과 다르면 복원 기회를 소진하지 않는다")
    void start_failure_sourceCountMismatch() {
        // given
        jdbc.update(
                "UPDATE document_topic_operations SET changed_count=4 WHERE id=?",
                source
        );
        // when & then
        assertThatThrownBy(() -> start()).isInstanceOf(DocumentException.class);
        assertThat(count("document_topic_operations")).isEqualTo(1);
        assertThat(count("document_topic_restoration_targets")).isZero();
        assertThat(recoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("복원 대상 테이블도 다른 Workspace 주제 연결을 FK로 거절한다")
    void persist_failure_crossWorkspaceCategory() {
        // given
        long id = start();
        long other = fixtures.saveWorkspace();
        long foreign = jdbc.queryForObject(
                "INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at) VALUES (?,'외부','외부','외부 주제','NORMAL',?) RETURNING id",
                Long.class,
                other,
                Timestamp.from(NOW)
        );
        // when & then
        assertThatThrownBy(
                () -> jdbc.update(
                        "UPDATE document_topic_restoration_targets SET restored_category_id=? WHERE operation_id=? AND document_id=?",
                        foreign,
                        id,
                        broadDocument
                )
        ).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("재정리로 새로 생긴 주제는 외부 참조가 없을 때만 배정 후보에서 제외한다")
    void restore_success_retiresUnreferencedGeneratedCategory() {
        // given
        jdbc.update(
                "UPDATE document_topic_operation_categories SET was_candidate=FALSE WHERE operation_id=? AND existing_category_id=?",
                source,
                merged
        );
        assign(
                newDocument,
                original,
                "RULE_REUSE",
                "new-rule"
        );
        assign(
                unchangedDocument,
                original,
                "RULE_REUSE",
                "unchanged-rule"
        );
        long id = start();
        // when
        finish(id);
        // then
        assertThat(availability(merged)).isFalse();
        assertThat(availability(original)).isTrue();
        assertThat(count("document_categories")).isEqualTo(3);
    }

    @Test
    @DisplayName("재정리로 생성된 주제라도 복원 중 새 문서가 참조하면 가용성을 변경하지 않는다")
    void restore_success_keepsGeneratedCategoryForLaterDocument() {
        // given
        jdbc.update(
                "UPDATE document_topic_operation_categories SET was_candidate=FALSE WHERE operation_id=? AND existing_category_id=?",
                source,
                merged
        );
        jdbc.update(
                "UPDATE document_categories SET active_for_assignment=FALSE WHERE id=?",
                merged
        );
        long id = start();
        long during = document();
        assign(
                during,
                merged,
                "RULE_REUSE",
                "during-rule"
        );
        // when
        finish(id);
        // then
        assertThat(availability(merged)).isFalse();
        assertThat(row(during)).containsEntry(
                "category_id",
                merged
        );
        assertThat(row(newDocument)).containsEntry(
                "category_id",
                merged
        );
    }

    @Test
    @DisplayName("적용 전 복원 포인터가 없어졌으면 어떠한 문서도 복원하지 않는다")
    void restore_failure_lostRecovery() {
        // given
        long id = start();
        jdbc.update(
                "DELETE FROM document_topic_recoveries WHERE workspace_id=?",
                workspace
        );
        // when
        finish(id);
        // then
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(row(legacyDocument)).containsEntry(
                "category_id",
                merged
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("서로 다른 DB 연결에서 취소·적용 진입은 먼저 잠금을 확보해 커밋한 쪽만 성공한다")
    void cancel_success_databaseRace(boolean cancelFirst) throws Exception {
        // given
        long id = start();
        DocumentTopicRestorationExecution execution = prepare(id);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject(
                        "SELECT id FROM workspaces WHERE id=? FOR UPDATE",
                        Long.class,
                        workspace
                );
                firstAction(
                        cancelFirst,
                        id,
                        execution
                );
                entered.countDown();
                await(release);
            }));
            assertThat(
                    entered.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<?> second = executor.submit(
                    () -> secondAction(
                            cancelFirst,
                            id,
                            execution
                    )
            );
            // when
            release.countDown();
            first.get(
                    10,
                    TimeUnit.SECONDS
            );
            second.get(
                    10,
                    TimeUnit.SECONDS
            );
            // then
            assertRaceResult(
                    cancelFirst,
                    id,
                    execution
            );
        } finally {
            release.countDown();
        }
    }

    private void firstAction(
            boolean cancelFirst,
            long id,
            DocumentTopicRestorationExecution execution
    ) {
        if (cancelFirst) {
            cancellations.request(
                    workspace,
                    owner,
                    id
            );
            return;
        }
        checkpoints.beginApply(execution);
    }

    private void secondAction(
            boolean cancelFirst,
            long id,
            DocumentTopicRestorationExecution execution
    ) {
        if (cancelFirst) {
            assertThat(checkpoints.beginApply(execution)).isFalse();
            return;
        }
        assertThatThrownBy(
                () -> cancellations.request(
                        workspace,
                        owner,
                        id
                )
        ).isInstanceOf(DocumentException.class);
    }

    private void assertRaceResult(
            boolean cancelFirst,
            long id,
            DocumentTopicRestorationExecution execution
    ) {
        if (cancelFirst) {
            cancellations.complete(
                    workspace,
                    id
            );
            assertThat(state(id)).isEqualTo("CANCELLED");
            assertThat(recoveryCount()).isEqualTo(1);
            return;
        }
        apply.apply(execution);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
        assertThat(recoveryCount()).isZero();
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

    private long start() {
        return service.start(
                workspace,
                owner,
                UUID.randomUUID(),
                version
        )
                .operationId();
    }

    private DocumentTopicRestorationExecution claim(long id) {
        return checkpoints.claim(
                workspace,
                id
        )
                .orElseThrow();
    }

    private DocumentTopicRestorationExecution prepare(long id) {
        checkpoints.prepare(claim(id));
        return claim(id);
    }

    private void finish(long id) {
        for (int i = 0; i < 4; i++) {
            worker.execute(
                    workspace,
                    id
            );
        }
    }

    private String state(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM document_topic_operations WHERE id=?",
                String.class,
                id
        );
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table,
                Integer.class
        );
    }

    private int recoveryCount() {
        return count("document_topic_recoveries");
    }

    private Map<String, Object> row(long documentId) {
        return jdbc.queryForMap(
                "SELECT * FROM documents WHERE id=?",
                documentId
        );
    }

    private boolean availability(long id) {
        return jdbc.queryForObject(
                "SELECT active_for_assignment FROM document_categories WHERE id=?",
                Boolean.class,
                id
        );
    }

    private long category(
            String name,
            String kind
    ) {
        return jdbc.queryForObject(
                "INSERT INTO document_categories(workspace_id,name,normalized_name,description,kind,created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                workspace,
                name,
                name,
                "녹음 품질",
                kind,
                Timestamp.from(NOW)
        );
    }

    private void assign(
            long document,
            long category,
            String source,
            String rule
    ) {
        jdbc.update(
                "UPDATE documents SET category_id=?,grouping_scheme='BROAD',assignment_source=?,assignment_rule_version=? WHERE id=?",
                category,
                source,
                rule,
                document
        );
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
                                        t -> "e:" + merged
                                )
                        ),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private void reject(String table) {
        jdbc.execute(
                "CREATE FUNCTION reject_restoration() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected storage failure'; END $$"
        );
        jdbc.execute(
                "CREATE TRIGGER reject_restoration BEFORE UPDATE OR DELETE ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION reject_restoration()"
        );
    }

    private void allow(String table) {
        jdbc.execute("DROP TRIGGER reject_restoration ON " + table);
        jdbc.execute("DROP FUNCTION reject_restoration()");
    }
}
