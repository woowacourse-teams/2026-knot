package com.knot.backend.search.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.sql.Timestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(30)
class SearchAnswerStopIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchAnswerSnapshotService snapshots;
    @Autowired
    private SearchHistoryQuery history;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private long memberId;
    private long workspaceId;
    private SearchQuestionAcceptedResult accepted;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("중지 사용자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
    }

    @Test
    @DisplayName("선점 전 중지는 빈 본문과 NULL lease를 보존하고 작업을 종료한다")
    void stop_success_queuedExecution() {
        assertThat(
                executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                )
                        .content()
        ).isEmpty();
        assertThat(snapshotStatus()).isEqualTo(SearchMessageStatus.STOPPED);
        assertThat(
                jdbc.sql("SELECT state FROM search_answer_executions")
                        .query(String.class)
                        .single()
        ).isEqualTo("FINISHED");
        assertThat(
                jdbc.sql(
                        "SELECT execution_token IS NULL AND deadline_at IS NULL AND stage IS NULL FROM search_answer_executions"
                )
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        assertThat(executions.claim(accepted.answerMessageId())).isEmpty();
        assertThat(executions.expire(accepted.answerMessageId())).isFalse();
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
    }

    @Test
    @DisplayName("부분 본문 보존과 중복 중지의 멱등성을 보장하고 모든 늦은 저장을 차단한다")
    void stop_success_preservesAndFences() {
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "부분 😀\n본문"
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        assertThat(
                executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
                        .content()
        ).isEqualTo("부분 😀\n본문");
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        assertThat(
                executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
                        .status()
        ).isEqualTo(SearchMessageStatus.STOPPED);
        assertThat(
                jdbc.sql("SELECT updated_at FROM search_conversations")
                        .query(Instant.class)
                        .single()
        ).isEqualTo(NOW.plusSeconds(1));
        assertThat(
                executions.append(
                        work.answerId(),
                        work.token(),
                        "늦은 출력"
                )
        ).isFalse();
        assertThat(
                executions.recordRetrieval(
                        work.answerId(),
                        work.token(),
                        0,
                        List.of()
                )
        ).isFalse();
        assertThat(
                executions.complete(
                        work.answerId(),
                        work.token(),
                        List.of(),
                        Set.of()
                )
        ).isFalse();
        assertThat(
                executions.fail(
                        work.answerId(),
                        work.token(),
                        "FAILURE"
                )
        ).isFalse();
        assertThat(executions.expire(work.answerId())).isFalse();
        assertThat(
                snapshots.read(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
                        .content()
        ).isEqualTo("부분 😀\n본문");
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_evidences")
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    @DisplayName("완료 또는 실패가 먼저 커밋되면 중지는 409이고 본문을 덮어쓰지 않는다")
    void stop_failure_terminalAlreadyCommitted(String status) {
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "보존"
        );
        finish(
                work,
                status
        );
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("답변이 생성 중이 아닙니다");
        assertThat(snapshotStatus().name()).isEqualTo(status);
    }

    @Test
    @DisplayName("중지는 완료한 답변의 실제 근거를 바꾸지 않고 늦은 근거 저장도 차단한다")
    void stop_success_evidenceImmutable() {
        long documentId = new SearchMessageFixtures(jdbc).saveDocument(
                workspaceId,
                memberId
        );
        jdbc.sql("UPDATE documents SET status='ARCHIVED', archived_at=created_at WHERE id=:id")
                .param(
                        "id",
                        documentId
                )
                .update();
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "문서 답변"
        );
        executions.complete(
                work.answerId(),
                work.token(),
                List.of(documentId),
                Set.of(documentId)
        );
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
        ).isInstanceOf(SearchException.class);
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences")
                        .query(Long.class)
                        .list()
        ).containsExactly(documentId);
        SearchQuestionAcceptedResult next = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "다른 질문",
                        UUID.randomUUID()
                )
        );
        SearchAnswerWorkResult nextWork = executions.claim(next.answerMessageId())
                .orElseThrow();
        executions.stop(
                workspaceId,
                memberId,
                next.conversationId(),
                nextWork.answerId()
        );
        assertThat(
                executions.complete(
                        nextWork.answerId(),
                        nextWork.token(),
                        List.of(documentId),
                        Set.of(documentId)
                )
        ).isFalse();
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences")
                        .query(Long.class)
                        .list()
        ).containsExactly(documentId);
    }

    @Test
    @DisplayName("멤버십·소유권·Workspace와 답변 소속을 확인한다")
    void stop_failure_accessAndAnswerScope() {
        long other = fixtures.saveMember("다른 사용자");
        fixtures.join(
                workspaceId,
                other
        );
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        other,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("탐색 대화에 접근할 수 없습니다");
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId + 1,
                        memberId,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("탐색 대화에 접근할 수 없습니다");
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        accepted.questionMessageId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("탐색 답변을 찾을 수 없습니다");
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        Long.MAX_VALUE
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("탐색 답변을 찾을 수 없습니다");
        jdbc.sql("DELETE FROM workspace_members")
                .update();
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("탐색 대화에 접근할 수 없습니다");
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE role='ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("STREAMING");
    }

    @Test
    @DisplayName("실행 metadata 누락은 중지 성공으로 위장하지 않는다")
    void stop_failure_missingExecution() {
        jdbc.sql("DELETE FROM search_answer_executions")
                .update();
        assertThatThrownBy(
                () -> executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessage("답변 생성이 준비되지 않았습니다");
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE role='ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("STREAMING");
    }

    @Test
    @DisplayName("DB 저장 실패는 메시지·작업·활동 시각 전체를 rollback한다")
    void stop_failure_atomicRollback() {
        jdbc.sql(
                "CREATE FUNCTION reject_search_stop() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'stop rejected'; END $$"
        )
                .update();
        jdbc.sql(
                "CREATE TRIGGER reject_search_stop BEFORE UPDATE ON search_answer_executions FOR EACH ROW EXECUTE FUNCTION reject_search_stop()"
        )
                .update();
        try {
            when(clock.instant()).thenReturn(NOW.plusSeconds(1));
            assertThatThrownBy(
                    () -> executions.stop(
                            workspaceId,
                            memberId,
                            accepted.conversationId(),
                            accepted.answerMessageId()
                    )
            ).isInstanceOf(RuntimeException.class);
            assertThat(snapshotStatus()).isEqualTo(SearchMessageStatus.STREAMING);
            assertThat(
                    jdbc.sql("SELECT state FROM search_answer_executions")
                            .query(String.class)
                            .single()
            ).isEqualTo("QUEUED");
            assertThat(
                    jdbc.sql("SELECT updated_at FROM search_conversations")
                            .query(Instant.class)
                            .single()
            ).isEqualTo(NOW);
        } finally {
            jdbc.sql("DROP TRIGGER reject_search_stop ON search_answer_executions")
                    .update();
            jdbc.sql("DROP FUNCTION reject_search_stop()")
                    .update();
        }
    }

    @Test
    @DisplayName("FINISHED lease의 일부 NULL은 DB에서 거절한다")
    void stop_failure_partialLeaseConstraint() {
        assertThatThrownBy(
                () -> jdbc.sql("UPDATE search_answer_executions SET state='FINISHED', execution_token=:token")
                        .param(
                                "token",
                                UUID.randomUUID()
                        )
                        .update()
        ).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("동시 중복 중지는 같은 본문과 종료 상태를 반환한다")
    void stop_success_concurrentDuplicates() throws Exception {
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "보존"
        );
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<String> stop = () -> {
                start.await();
                return executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
                        .content();
            };
            Future<String> first = executor.submit(stop);
            Future<String> second = executor.submit(stop);
            assertThat(first.get()).isEqualTo("보존");
            assertThat(second.get()).isEqualTo("보존");
        }
        assertThat(snapshotStatus()).isEqualTo(SearchMessageStatus.STOPPED);
    }

    @Test
    @DisplayName("대기 작업 선점과 중지가 경합해도 종료 작업이 재실행되지 않는다")
    void stop_success_concurrentQueuedClaim() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> stop = executor.submit(() -> {
                await(start);
                executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        accepted.answerMessageId()
                );
            });
            Future<?> claim = executor.submit(() -> {
                await(start);
                executions.claim(accepted.answerMessageId());
            });
            stop.get();
            claim.get();
        }
        assertThat(snapshotStatus()).isEqualTo(SearchMessageStatus.STOPPED);
        assertThat(executions.claim(accepted.answerMessageId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "APPEND", "RETRIEVAL", "EXPIRE"})
    @DisplayName("실제 DB 동시 경합에서 종료 상태는 하나이며 늦은 출력은 저장되지 않는다")
    void stop_success_concurrentGeneration(String action) throws Exception {
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "보존"
        );
        if (action.equals("EXPIRE")) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(151));
        }
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> stop = executor.submit(() -> {
                await(start);
                try {
                    executions.stop(
                            workspaceId,
                            memberId,
                            accepted.conversationId(),
                            work.answerId()
                    );
                } catch (SearchException exception) {
                    assertThat(exception.getMessage()).isEqualTo("답변이 생성 중이 아닙니다");
                }
            });
            Future<?> generate = executor.submit(() -> {
                await(start);
                finish(
                        work,
                        action
                );
            });
            stop.get();
            generate.get();
        }
        assertThat(snapshotStatus()).isIn(
                SearchMessageStatus.STOPPED,
                SearchMessageStatus.COMPLETED,
                SearchMessageStatus.FAILED
        );
        String content = snapshots.read(
                workspaceId,
                memberId,
                accepted.conversationId(),
                work.answerId()
        )
                .content();
        assertThat(
                executions.append(
                        work.answerId(),
                        work.token(),
                        "더 늦은 출력"
                )
        ).isFalse();
        assertThat(
                snapshots.read(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        work.answerId()
                )
                        .content()
        ).isEqualTo(content);
    }

    @Test
    @DisplayName("중지된 부분 답변은 다음 질문 문맥에 포함한다")
    void stop_success_followupHistory() {
        SearchAnswerWorkResult work = claim();
        executions.append(
                work.answerId(),
                work.token(),
                "부분 답변"
        );
        executions.stop(
                workspaceId,
                memberId,
                accepted.conversationId(),
                work.answerId()
        );
        long question = jdbc.sql(
                "INSERT INTO search_messages(conversation_id,role,sequence,content,status,created_at) VALUES (:conversation,'USER',3,'후속','RECEIVED',:now) RETURNING id"
        )
                .param(
                        "conversation",
                        accepted.conversationId()
                )
                .param(
                        "now",
                        Timestamp.from(NOW)
                )
                .query(Long.class)
                .single();
        SearchToolScope scope = new SearchToolScope(
                workspaceId,
                memberId,
                accepted.conversationId(),
                question,
                work.answerId(),
                UUID.randomUUID(),
                NOW,
                NOW.plusSeconds(30)
        );
        assertThat(history.beforeQuestion(scope)).extracting(row -> row.get("content"))
                .containsExactly(
                        "질문",
                        "부분 답변"
                );
    }

    private SearchMessageStatus snapshotStatus() {
        return snapshots.read(
                workspaceId,
                memberId,
                accepted.conversationId(),
                accepted.answerMessageId()
        )
                .status();
    }

    private SearchAnswerWorkResult claim() {
        return executions.claim(accepted.answerMessageId())
                .orElseThrow();
    }

    private void finish(
            SearchAnswerWorkResult work,
            String action
    ) {
        if (action.equals("COMPLETED")) {
            executions.complete(
                    work.answerId(),
                    work.token(),
                    List.of(),
                    Set.of()
            );
            return;
        }
        if (action.equals("FAILED")) {
            // Legacy FAILED remains terminal even though new technical errors store
            // guidance.
            jdbc.sql("UPDATE search_messages SET status = 'FAILED', failure_code = 'FAILURE' WHERE id = :id")
                    .param(
                            "id",
                            work.answerId()
                    )
                    .update();
            jdbc.sql("UPDATE search_answer_executions SET state = 'FINISHED' WHERE answer_id = :id")
                    .param(
                            "id",
                            work.answerId()
                    )
                    .update();
            return;
        }
        if (action.equals("APPEND")) {
            executions.append(
                    work.answerId(),
                    work.token(),
                    "새 출력"
            );
            return;
        }
        if (action.equals("RETRIEVAL")) {
            executions.recordRetrieval(
                    work.answerId(),
                    work.token(),
                    0,
                    List.of()
            );
            return;
        }
        if (action.equals("EXPIRE")) {
            executions.expire(work.answerId());
            return;
        }
        executions.claim(work.answerId());
    }

    private void await(CyclicBarrier start) {
        try {
            start.await();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
