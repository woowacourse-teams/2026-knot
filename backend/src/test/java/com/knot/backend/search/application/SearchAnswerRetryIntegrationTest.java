package com.knot.backend.search.application;

import static org.mockito.Mockito.when;
import com.knot.backend.search.domain.model.SearchIntent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.query.SearchConversationListParameters;
import com.knot.backend.search.application.dto.result.SearchAnswerStopResult;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.model.SearchRequestReceipt;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.UUID;
import java.time.Instant;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchAnswerRetryIntegrationTest {

    @Autowired
    private SearchAnswerRetryService retries;
    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchAnswerSnapshotService snapshots;
    @Autowired
    private SearchHistoryQuery history;
    @Autowired
    private SearchConversationListService conversations;
    @Autowired
    private JdbcClient jdbc;
    @MockitoSpyBean
    private SearchRepository repository;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchAnswerGenerator generator;
    private DocumentFixtures fixtures;
    private long workspace;
    private long member;
    private SearchQuestionAcceptedResult first;
    private SearchAnswerWorkResult old;

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "WRONG_QUESTION"})
    @DisplayName("실패 답변의 실행 연결 또는 종료 상태가 손상되면 재시도를 거절한다")
    void retry_failure_invalidExecution(String scenario) {
        if ("QUEUED".equals(scenario)) {
            jdbc.sql(
                    "UPDATE search_answer_executions SET state='QUEUED', execution_token=NULL, deadline_at=NULL, stage=NULL WHERE answer_id=:id"
            )
                    .param(
                            "id",
                            first.answerMessageId()
                    )
                    .update();
        }
        if ("WRONG_QUESTION".equals(scenario)) {
            long question = jdbc.sql(
                    "INSERT INTO search_messages(conversation_id,role,sequence,content,status,created_at) VALUES (:id,'USER',3,'다른 질문','RECEIVED',CURRENT_TIMESTAMP) RETURNING id"
            )
                    .param(
                            "id",
                            first.conversationId()
                    )
                    .query(Long.class)
                    .single();
            jdbc.sql("UPDATE search_answer_executions SET question_id=:q WHERE answer_id=:id")
                    .param(
                            "q",
                            question
                    )
                    .param(
                            "id",
                            first.answerMessageId()
                    )
                    .update();
        }
        assertThatThrownBy(() -> retry(UUID.randomUUID())).isInstanceOf(SearchException.class)
                .satisfies(
                        exception -> assertThat(((SearchException) exception).getErrorCode())
                                .isEqualTo(SearchErrorCode.INVALID_SEARCH_STATE)
                );
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE id=:id")
                        .param(
                                "id",
                                first.answerMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    @BeforeEach
    void setUp() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        member = fixtures.saveMember("재시도 질문자");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        first = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "처음 질문",
                        UUID.randomUUID()
                )
        );
        old = executions.claim(first.answerMessageId())
                .orElseThrow();
        executions.recordRetrieval(
                old.answerId(),
                old.token(),
                2,
                List.of()
        );
        executions.append(
                old.answerId(),
                old.token(),
                "실패한 부분 답변"
        );
        legacyFailure(
                old,
                "ANSWER_GENERATION_FAILED"
        );
    }

    @Test
    @DisplayName("재시도는 기존 메시지·순서를 유지하고 오류·진행 상태를 교체한다")
    void acceptRetry_success_atomicReset() {
        // when
        SearchQuestionAcceptedResult accepted = retry(UUID.randomUUID());
        // then
        assertThat(accepted.questionMessageId()).isEqualTo(first.questionMessageId());
        assertThat(accepted.answerMessageId()).isEqualTo(old.answerId());
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_answer_executions")).isEqualTo(1);
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT sequence FROM search_messages ORDER BY sequence")
                        .query(Integer.class)
                        .list()
        ).containsExactly(
                1,
                2
        );
        assertThat(
                jdbc.sql("SELECT status, content, failure_code FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                old.answerId()
                        )
                        .query()
                        .singleRow()
        ).containsEntry(
                "status",
                "STREAMING"
        )
                .containsEntry(
                        "content",
                        ""
                )
                .containsEntry(
                        "failure_code",
                        null
                );
        assertThat(
                jdbc.sql(
                        "SELECT state, execution_token, deadline_at, stage, excluded_count FROM search_answer_executions"
                )
                        .query()
                        .singleRow()
        ).containsEntry(
                "state",
                "QUEUED"
        )
                .containsEntry(
                        "execution_token",
                        null
                )
                .containsEntry(
                        "deadline_at",
                        null
                )
                .containsEntry(
                        "stage",
                        null
                )
                .containsEntry(
                        "excluded_count",
                        null
                );
        assertThat(visible()).isFalse();
        SearchAnswerWorkResult current = executions.claim(old.answerId())
                .orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(
                executions.append(
                        old.answerId(),
                        old.token(),
                        "늦은 출력"
                )
        ).isFalse();
        assertThat(
                executions.recordRetrieval(
                        old.answerId(),
                        old.token(),
                        0,
                        List.of()
                )
        ).isFalse();
        assertThat(
                executions.complete(
                        old.answerId(),
                        old.token(),
                        List.of(),
                        Set.of()
                )
        ).isFalse();
        assertThat(
                executions.fail(
                        old.answerId(),
                        old.token(),
                        "OLD_FAILURE"
                )
        ).isFalse();
        executions.append(
                current.answerId(),
                current.token(),
                "새 답변"
        );
        assertThat(
                executions.complete(
                        current.answerId(),
                        current.token(),
                        List.of(),
                        Set.of()
                )
        ).isTrue();
        assertThat(visible()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("첫 실패 재시도는 생성 전과 부분 생성 후 중지 모두 목록을 복원하고 후속 문맥을 보존한다")
    void acceptRetry_success_stoppedRestoresVisibility(boolean partialAnswer) {
        // given
        SearchQuestionAcceptedResult accepted = retry(UUID.randomUUID());
        assertThat(visible()).isFalse();
        String content = appendRetryContent(partialAnswer);
        // when
        SearchAnswerStopResult stopped = executions.stop(
                workspace,
                member,
                first.conversationId(),
                accepted.answerMessageId()
        );
        // then
        assertThat(stopped.status()).isEqualTo(SearchMessageStatus.STOPPED);
        assertThat(stopped.content()).isEqualTo(content);
        assertThat(visible()).isTrue();
        assertThat(
                conversations.find(
                        workspace,
                        member,
                        new SearchConversationListParameters(
                                null,
                                20
                        )
                )
                        .items()
        ).extracting(item -> item.id())
                .containsExactly(first.conversationId());
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(
                executions.stop(
                        workspace,
                        member,
                        first.conversationId(),
                        accepted.answerMessageId()
                )
        ).isEqualTo(stopped);
        assertError(
                member,
                first.questionMessageId(),
                "ANSWER_NOT_FAILED"
        );
        SearchQuestionAcceptedResult next = intake.acceptNext(
                workspace,
                member,
                first.conversationId(),
                new SearchQuestionCommand(
                        "이후 질문",
                        UUID.randomUUID()
                )
        );
        Instant startedAt = Instant.now();
        assertThat(
                history.beforeQuestion(
                        new SearchToolScope(
                                workspace,
                                member,
                                first.conversationId(),
                                next.questionMessageId(),
                                next.answerMessageId(),
                                UUID.randomUUID(),
                                startedAt,
                                startedAt.plusSeconds(60)
                        )
                )
        ).containsExactly(
                Map.of(
                        "role",
                        "user",
                        "content",
                        "처음 질문"
                ),
                Map.of(
                        "role",
                        "assistant",
                        "content",
                        content
                )
        );
    }

    private String appendRetryContent(boolean partialAnswer) {
        if (!partialAnswer) {
            return "";
        }
        SearchAnswerWorkResult current = executions.claim(old.answerId())
                .orElseThrow();
        assertThat(
                executions.append(
                        current.answerId(),
                        current.token(),
                        "중지한 부분 답변"
                )
        ).isTrue();
        return "중지한 부분 답변";
    }

    @Test
    @DisplayName("동일 UUID는 생성 중·가용성 검사보다 먼저 기존 시도를 복구한다")
    void acceptRetry_success_idempotentBeforeBusy() {
        // given
        UUID request = UUID.randomUUID();
        SearchQuestionAcceptedResult accepted = retry(request);
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        // when & then
        assertThat(retry(request)).isEqualTo(accepted);
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertError(
                member,
                first.questionMessageId(),
                "ANSWER_ALREADY_IN_PROGRESS"
        );
        doNothing().when(availability)
                .requireAvailable();
        SearchAnswerWorkResult current = executions.claim(old.answerId())
                .orElseThrow();
        legacyFailure(
                current,
                "NEW_FAILURE"
        );
        assertThat(visible()).isFalse();
        SearchQuestionAcceptedResult later = retry(UUID.randomUUID());
        assertThat(retry(request)).isEqualTo(accepted);
        assertThat(later.receiptId()).isNotEqualTo(accepted.receiptId());
        assertThat(
                snapshots.read(
                        workspace,
                        member,
                        old.answerId()
                )
                        .receiptId()
        ).isEqualTo(later.receiptId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "STOPPED"})
    @DisplayName("FAILED가 아닌 답변은 새 재시도를 거절한다")
    void acceptRetry_failure_notFailed(String status) {
        // given
        jdbc.sql("UPDATE search_messages SET status = :status, failure_code = NULL WHERE id = :id")
                .param(
                        "status",
                        status
                )
                .param(
                        "id",
                        old.answerId()
                )
                .update();
        // when & then
        assertError(
                member,
                first.questionMessageId(),
                "ANSWER_NOT_FAILED"
        );
        assertThat(count("search_request_receipts")).isEqualTo(1);
    }

    @Test
    @DisplayName("소유권·현재 멤버십은 기존 접수 복구보다 먼저 검증한다")
    void acceptRetry_failure_scope() {
        // given
        UUID request = UUID.randomUUID();
        retry(request);
        long other = fixtures.saveMember("다른 멤버");
        fixtures.join(
                workspace,
                other
        );
        // when & then
        assertError(
                other,
                first.questionMessageId(),
                "SEARCH_ACCESS_DENIED"
        );
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP WHERE member_id = :id")
                .param(
                        "id",
                        member
                )
                .update();
        assertThatThrownBy(() -> retry(request)).isInstanceOf(SearchException.class)
                .hasMessageContaining("접근할 수 없습니다");
    }

    @Test
    @DisplayName("다른 역할·대화의 질문과 requestId의 다른 대상은 거절한다")
    void acceptRetry_failure_invalidPairAndTargetConflict() {
        // given
        UUID request = UUID.randomUUID();
        retry(request);
        // when & then
        assertError(
                member,
                old.answerId(),
                "QUESTION_NOT_FOUND"
        );
        assertError(
                member,
                99999,
                "QUESTION_NOT_FOUND"
        );
        assertThatThrownBy(
                () -> retries.acceptRetry(
                        workspace,
                        member,
                        first.conversationId(),
                        99999,
                        request
                )
        ).isInstanceOf(SearchException.class)
                .hasMessageContaining("requestId");
        SearchQuestionAcceptedResult other = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "다른 대화",
                        UUID.randomUUID()
                )
        );
        assertThatThrownBy(
                () -> retries.acceptRetry(
                        workspace,
                        member,
                        other.conversationId(),
                        first.questionMessageId(),
                        UUID.randomUUID()
                )
        ).isInstanceOf(SearchException.class)
                .hasMessageContaining("질문을 찾을 수");
    }

    @Test
    @DisplayName("접수 저장 실패는 본문·실행 초기화를 모두 되돌린다")
    void acceptRetry_failure_atomicRollback() {
        // given
        doThrow(new IllegalStateException("receipt failed")).when(repository)
                .save(any(SearchRequestReceipt.class));
        // when & then
        assertThatThrownBy(() -> retry(UUID.randomUUID())).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("receipt failed");
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                old.answerId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("실패한 부분 답변");
        assertThat(
                jdbc.sql("SELECT state FROM search_answer_executions")
                        .query(String.class)
                        .single()
        ).isEqualTo("FINISHED");
        assertThat(count("search_request_receipts")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("동시 재시도는 같은 UUID를 복구하거나 다른 요청 하나만 접수한다")
    void acceptRetry_success_concurrentRequests(boolean sameRequest) throws Exception {
        // given
        UUID request = UUID.randomUUID();
        UUID second = request;
        if (!sameRequest) {
            second = UUID.randomUUID();
        }
        UUID next = second;
        CyclicBarrier barrier = new CyclicBarrier(2);
        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> one = pool.submit(
                    () -> race(
                            barrier,
                            request
                    )
            );
            Future<String> two = pool.submit(
                    () -> race(
                            barrier,
                            next
                    )
            );
            List<String> results = List.of(
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
            assertThat(results).contains("ACCEPTED");
            if (sameRequest) {
                assertThat(results).containsOnly("ACCEPTED");
            }
            if (!sameRequest) {
                assertThat(results).contains("ANSWER_ALREADY_IN_PROGRESS");
            }
        }
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertThat(count("search_messages")).isEqualTo(2);
    }

    @Test
    @DisplayName("과거 질문 재시도는 뒤쪽 문맥을 끌어오지 않고 다른 활성 생성을 막는다")
    void acceptRetry_success_historyBeforeQuestion() {
        // given
        SearchQuestionAcceptedResult next = intake.acceptNext(
                workspace,
                member,
                first.conversationId(),
                new SearchQuestionCommand(
                        "나중 질문",
                        UUID.randomUUID()
                )
        );
        assertError(
                member,
                first.questionMessageId(),
                "ANSWER_ALREADY_IN_PROGRESS"
        );
        SearchAnswerWorkResult work = executions.claim(next.answerMessageId())
                .orElseThrow();
        executions.append(
                work.answerId(),
                work.token(),
                "나중 답변"
        );
        executions.complete(
                work.answerId(),
                work.token(),
                List.of(),
                Set.of()
        );
        // when
        retry(UUID.randomUUID());
        // then
        assertThat(
                history.beforeQuestion(
                        new SearchToolScope(
                                workspace,
                                member,
                                first.conversationId(),
                                first.questionMessageId(),
                                first.answerMessageId(),
                                UUID.randomUUID(),
                                Instant.now(),
                                Instant.now()
                                        .plusSeconds(60)
                        )
                )
        ).isEmpty();
        assertThat(count("search_messages")).isEqualTo(4);
    }

    // Seed messages stored before guidance completion was introduced; do not use
    // the new failure policy to create them.
    private void legacyFailure(
            SearchAnswerWorkResult work,
            String code
    ) {
        jdbc.sql("UPDATE search_messages SET status = 'FAILED', failure_code = :code WHERE id = :id")
                .param(
                        "code",
                        code
                )
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
        jdbc.sql("UPDATE search_conversations SET visible_in_list = false WHERE id = :id")
                .param(
                        "id",
                        work.conversationId()
                )
                .update();
    }

    private SearchQuestionAcceptedResult retry(UUID request) {
        return retries.acceptRetry(
                workspace,
                member,
                first.conversationId(),
                first.questionMessageId(),
                request
        );
    }

    @Test
    @DisplayName("재시도는 기존 근거를 삭제하고 새 답변의 사용 근거만 저장한다")
    void acceptRetry_success_replaceEvidence() {
        // given
        long document = saveEvidence();
        // when
        retry(UUID.randomUUID());
        // then
        assertThat(count("search_evidences")).isZero();
        SearchAnswerWorkResult work = executions.claim(first.answerMessageId())
                .orElseThrow();
        executions.append(
                work.answerId(),
                work.token(),
                "새 근거 답변"
        );
        assertThat(
                executions.complete(
                        work.answerId(),
                        work.token(),
                        List.of(document),
                        Set.of(document)
                )
        ).isTrue();
        assertThat(count("search_evidences")).isEqualTo(1);
        assertThat(
                snapshots.read(
                        workspace,
                        member,
                        first.answerMessageId()
                )
                        .evidences()
        ).extracting(item -> item.documentId())
                .containsExactly(document);
    }

    @Test
    @DisplayName("접수 실패는 이전 근거 삭제도 롤백한다")
    void acceptRetry_failure_rollbackEvidence() {
        // given
        saveEvidence();
        doThrow(new IllegalStateException("receipt failed")).when(repository)
                .save(any(SearchRequestReceipt.class));
        // when & then
        assertThatThrownBy(() -> retry(UUID.randomUUID())).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(count("search_evidences")).isEqualTo(1);
        assertThat(
                snapshots.read(
                        workspace,
                        member,
                        first.answerMessageId()
                )
                        .content()
        ).isEqualTo("실패한 부분 답변");
    }

    private long saveEvidence() {
        long recording = fixtures.saveRecording(
                workspace,
                member,
                1000
        );
        long transcript = fixtures.saveTranscript(recording);
        long job = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        long document = fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                job,
                "재시도 근거"
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
                .param(
                        "id",
                        document
                )
                .update();
        jdbc.sql("INSERT INTO search_evidences (message_id, document_id, rank) VALUES (:message, :document, 1)")
                .param(
                        "message",
                        first.answerMessageId()
                )
                .param(
                        "document",
                        document
                )
                .update();
        return document;
    }

    private void assertError(
            long owner,
            long question,
            String code
    ) {
        assertThatThrownBy(
                () -> retries.acceptRetry(
                        workspace,
                        owner,
                        first.conversationId(),
                        question,
                        UUID.randomUUID()
                )
        ).isInstanceOf(SearchException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        SearchErrorCode.valueOf(code)
                );
    }

    private String race(
            CyclicBarrier barrier,
            UUID request
    ) throws Exception {
        barrier.await(
                5,
                TimeUnit.SECONDS
        );
        try {
            retry(request);
            return "ACCEPTED";
        } catch (SearchException exception) {
            return exception.getErrorCode()
                    .getCode();
        }
    }

    @Test
    @DisplayName("후속 질문과 재시도가 경합하면 생성 작업은 하나만 접수한다")
    void acceptRetry_success_raceWithFollowup() throws Exception {
        // given
        CyclicBarrier barrier = new CyclicBarrier(2);
        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> retry = pool.submit(
                    () -> race(
                            barrier,
                            UUID.randomUUID()
                    )
            );
            Future<String> question = pool.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                try {
                    intake.acceptNext(
                            workspace,
                            member,
                            first.conversationId(),
                            new SearchQuestionCommand(
                                    "동시 후속 질문",
                                    UUID.randomUUID()
                            )
                    );
                    return "ACCEPTED";
                } catch (SearchException exception) {
                    return exception.getErrorCode()
                            .getCode();
                }
            });
            // then
            assertThat(
                    List.of(
                            retry.get(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                            question.get(
                                    10,
                                    TimeUnit.SECONDS
                            )
                    )
            ).containsExactlyInAnyOrder(
                    "ACCEPTED",
                    "ANSWER_ALREADY_IN_PROGRESS"
            );
        }
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_messages WHERE status = 'STREAMING'")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("질문 다음 순서에 ASSISTANT 답변이 없으면 접수하지 않는다")
    void acceptRetry_failure_brokenQuestionAnswerPair(boolean missingSequence) {
        // given
        if (missingSequence) {
            jdbc.sql("UPDATE search_messages SET sequence = 3 WHERE id = :id")
                    .param(
                            "id",
                            old.answerId()
                    )
                    .update();
        }
        if (!missingSequence) {
            jdbc.sql(
                    "UPDATE search_messages SET role = 'USER', status = 'RECEIVED', failure_code = NULL WHERE id = :id"
            )
                    .param(
                            "id",
                            old.answerId()
                    )
                    .update();
        }
        // when & then
        assertError(
                member,
                first.questionMessageId(),
                "QUESTION_NOT_FOUND"
        );
        assertThat(count("search_request_receipts")).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private boolean visible() {
        return jdbc.sql("SELECT visible_in_list FROM search_conversations WHERE id = :id")
                .param(
                        "id",
                        first.conversationId()
                )
                .query(Boolean.class)
                .single();
    }
}
