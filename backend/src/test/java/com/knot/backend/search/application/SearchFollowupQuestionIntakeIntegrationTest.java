package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import static org.mockito.Mockito.when;
import com.knot.backend.search.domain.model.SearchIntent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.model.SearchRequestOperation;
import com.knot.backend.search.domain.model.SearchRequestReceipt;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class SearchFollowupQuestionIntakeIntegrationTest {

    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
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

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "USER", "SEQUENCE"})
    @DisplayName("손상된 대화의 후속 접수는 커스텀 오류로 거절하고 추가 저장하지 않는다")
    void acceptNext_failure_invalidStoredConversation(String scenario) {
        java.util.Optional<com.knot.backend.search.domain.model.SearchMessage> last = java.util.Optional.empty();
        if ("USER".equals(scenario)) {
            last = java.util.Optional.of(
                    com.knot.backend.search.domain.model.SearchMessage.receiveQuestion(
                            first.conversationId(),
                            1,
                            "질문",
                            Instant.now()
                    )
            );
        }
        if ("SEQUENCE".equals(scenario)) {
            last = java.util.Optional.of(
                    com.knot.backend.search.domain.model.SearchMessage.startAnswer(
                            first.conversationId(),
                            Integer.MAX_VALUE,
                            Instant.now()
                    )
            );
        }
        org.mockito.Mockito.doReturn(last)
                .when(repository)
                .findLastMessage(first.conversationId());
        assertThatThrownBy(
                () -> accept(
                        UUID.randomUUID(),
                        "후속 질문"
                )
        ).isInstanceOf(SearchException.class)
                .satisfies(
                        exception -> assertThat(((SearchException) exception).getErrorCode())
                                .isEqualTo(SearchErrorCode.INVALID_SEARCH_STATE)
                );
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(1);
    }

    @BeforeEach
    void setUp() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchUsePlan.of(SearchIntent.MEETING_SEARCH));
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        member = fixtures.saveMember("후속 질문자");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        first = intake.acceptFirst(
                workspace,
                member,
                command(
                        UUID.randomUUID(),
                        "처음 질문"
                )
        );
        finishFirst("COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "STOPPED", "FAILED"})
    @DisplayName("종료된 답변 뒤 같은 대화에 연속 순서와 원문 및 실행 의도를 저장한다")
    void acceptNext_success_atomicAppend(String status) {
        // given
        finishFirst(status);
        Instant created = time("created_at");
        jdbc.sql("UPDATE search_conversations SET updated_at = created_at - INTERVAL '1 day'")
                .update();
        UUID request = UUID.randomUUID();
        // when
        SearchQuestionAcceptedResult next = accept(
                request,
                " 후속 😀 질문 "
        );
        // then
        assertThat(next.conversationId()).isEqualTo(first.conversationId());
        assertThat(next.answerMessageId()).isNotEqualTo(first.answerMessageId());
        assertThat(count("search_conversations")).isEqualTo(1);
        assertThat(count("search_messages")).isEqualTo(4);
        assertThat(count("search_answer_executions")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT sequence FROM search_messages ORDER BY sequence")
                        .query(Integer.class)
                        .list()
        ).containsExactly(
                1,
                2,
                3,
                4
        );
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                next.questionMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(" 후속 😀 질문 ");
        assertThat(
                repository.findQuestionReceipt(
                        workspace,
                        member,
                        first.conversationId(),
                        request
                )
        ).get()
                .extracting(
                        SearchRequestReceipt::getOperation,
                        SearchRequestReceipt::getTargetId
                )
                .containsExactly(
                        SearchRequestOperation.QUESTION,
                        first.conversationId()
                );
        assertThat(repository.findLastMessage(first.conversationId())).get()
                .extracting(message -> message.getId())
                .isEqualTo(next.answerMessageId());
        assertThat(repository.existsStreamingAnswer(first.conversationId())).isTrue();
        assertThat(time("created_at")).isEqualTo(created);
        assertThat(time("updated_at")).isAfter(created.minusSeconds(86400));
        assertThat(
                jdbc.sql("SELECT state FROM search_answer_executions WHERE answer_id = :id")
                        .param(
                                "id",
                                next.answerMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("접수 조회는 요청 종류·대화·Workspace·Member를 구분한다")
    void findQuestionReceipt_success_scopeIsolation() {
        // given
        UUID request = UUID.randomUUID();
        accept(
                request,
                "후속"
        );
        // when & then
        assertThat(
                repository.findQuestionReceipt(
                        workspace,
                        member,
                        first.conversationId() + 1,
                        request
                )
        ).isEmpty();
        assertThat(
                repository.findQuestionReceipt(
                        workspace + 1,
                        member,
                        first.conversationId(),
                        request
                )
        ).isEmpty();
        assertThat(
                repository.findQuestionReceipt(
                        workspace,
                        member + 1,
                        first.conversationId(),
                        request
                )
        ).isEmpty();
        assertThat(
                repository.findFirstReceipt(
                        workspace,
                        member,
                        request
                )
        ).isEmpty();
        assertThat(repository.findLastMessage(Long.MAX_VALUE)).isEmpty();
        assertThat(repository.existsStreamingAnswer(Long.MAX_VALUE)).isFalse();
    }

    @Test
    @DisplayName("같은 요청은 생성 중 검사와 가용성 검사보다 먼저 기존 ID로 복구한다")
    void acceptNext_success_replayBeforeAvailability() {
        // given
        UUID request = UUID.randomUUID();
        SearchQuestionAcceptedResult next = accept(
                request,
                "후속"
        );
        Instant updated = time("updated_at");
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        // when & then
        assertThat(
                accept(
                        request,
                        "후속"
                )
        ).isEqualTo(next);
        assertThatThrownBy(
                () -> accept(
                        request,
                        " 후속"
                )
        ).isInstanceOf(SearchException.class)
                .hasMessageContaining("requestId");
        assertThat(count("search_messages")).isEqualTo(4);
        assertThat(time("updated_at")).isEqualTo(updated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("QUEUED 상태도 새 질문은 진행 중 오류로 거절한다")
    void acceptNext_failure_queuedAnswer(boolean running) {
        // given
        SearchQuestionAcceptedResult next = accept(
                UUID.randomUUID(),
                "후속"
        );
        if (running) {
            executions.claim(next.answerMessageId())
                    .orElseThrow();
        }
        // when & then
        assertCode(
                () -> accept(
                        UUID.randomUUID(),
                        "다음"
                ),
                "ANSWER_ALREADY_IN_PROGRESS"
        );
        assertThat(count("search_messages")).isEqualTo(4);
    }

    @Test
    @DisplayName("다른 소유자·Workspace·탈퇴 멤버는 접수하지 않는다")
    void acceptNext_failure_accessDenied() {
        // given
        long other = fixtures.saveMember("다른 사람");
        fixtures.join(
                workspace,
                other
        );
        long otherWorkspace = fixtures.saveWorkspace();
        fixtures.join(
                otherWorkspace,
                member
        );
        // when & then
        assertCode(
                () -> intake.acceptNext(
                        workspace,
                        other,
                        first.conversationId(),
                        command(
                                UUID.randomUUID(),
                                "후속"
                        )
                ),
                "SEARCH_ACCESS_DENIED"
        );
        assertCode(
                () -> intake.acceptNext(
                        otherWorkspace,
                        member,
                        first.conversationId(),
                        command(
                                UUID.randomUUID(),
                                "후속"
                        )
                ),
                "SEARCH_ACCESS_DENIED"
        );
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        assertCode(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                ),
                "SEARCH_ACCESS_DENIED"
        );
        assertUnchanged();
    }

    @Test
    @DisplayName("현재 멤버는 없는 대화와 잘못된 식별자를 구분한다")
    void acceptNext_failure_invalidTarget() {
        // when & then
        assertCode(
                () -> intake.acceptNext(
                        workspace,
                        member,
                        Long.MAX_VALUE,
                        command(
                                UUID.randomUUID(),
                                "후속"
                        )
                ),
                "CONVERSATION_NOT_FOUND"
        );
        assertCode(
                () -> intake.acceptNext(
                        workspace,
                        member,
                        0,
                        command(
                                UUID.randomUUID(),
                                "후속"
                        )
                ),
                "INVALID_PARAMETER"
        );
        assertUnchanged();
    }

    @Test
    @DisplayName("생성이 불가능한 신규 접수는 메시지를 저장하지 않는다")
    void acceptNext_failure_unavailable() {
        // given
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        // when & then
        assertCode(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                ),
                "ANSWER_GENERATION_UNAVAILABLE"
        );
        assertUnchanged();
    }

    @Test
    @DisplayName("실행 저장 실패는 메시지·receipt·updatedAt까지 롤백한다")
    void acceptNext_failure_atomicRollback() {
        // given
        Instant updated = time("updated_at");
        doThrow(new IllegalStateException("저장 실패")).when(repository)
                .save(any(SearchAnswerExecution.class));
        // when & then
        assertThatThrownBy(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                )
        ).hasCauseInstanceOf(IllegalStateException.class);
        assertUnchanged();
        assertThat(time("updated_at")).isEqualTo(updated);
    }

    @Test
    @DisplayName("동시에 같은 UUID를 보내도 새 쌍 하나와 같은 ID를 반환한다")
    void acceptNext_success_concurrentDuplicate() throws Exception {
        // given
        UUID request = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SearchQuestionAcceptedResult> left = executor.submit(() -> {
                start.await();
                return accept(
                        request,
                        "후속"
                );
            });
            Future<SearchQuestionAcceptedResult> right = executor.submit(() -> {
                start.await();
                return accept(
                        request,
                        "후속"
                );
            });
            // then
            assertThat(
                    left.get(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isEqualTo(
                    right.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
        assertThat(count("search_messages")).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("다른 요청 및 같은 키의 다른 본문 경합은 한 요청만 수락한다")
    void acceptNext_failure_concurrentQuestion(boolean sameKey) throws Exception {
        // given
        UUID leftKey = UUID.randomUUID();
        UUID rightKey = UUID.randomUUID();
        if (sameKey) {
            rightKey = leftKey;
        }
        UUID secondKey = rightKey;
        CyclicBarrier start = new CyclicBarrier(2);
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> left = executor.submit(
                    () -> attempt(
                            start,
                            leftKey,
                            "질문 A"
                    )
            );
            Future<String> right = executor.submit(
                    () -> attempt(
                            start,
                            secondKey,
                            "질문 B"
                    )
            );
            String rejected = "ANSWER_ALREADY_IN_PROGRESS";
            if (sameKey) {
                rejected = "REQUEST_ID_CONFLICT";
            }
            // then
            assertThat(
                    List.of(
                            left.get(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                            right.get(
                                    10,
                                    TimeUnit.SECONDS
                            )
                    )
            ).containsExactlyInAnyOrder(
                    "ACCEPTED",
                    rejected
            );
        }
        assertThat(count("search_messages")).isEqualTo(4);
        assertThat(count("search_request_receipts")).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 대화는 같은 UUID와 동시 생성 상태를 독립적으로 가진다")
    void acceptNext_success_independentConversations() {
        // given
        SearchQuestionAcceptedResult other = intake.acceptFirst(
                workspace,
                member,
                command(
                        UUID.randomUUID(),
                        "다른 대화"
                )
        );
        jdbc.sql("UPDATE search_messages SET status = 'STOPPED' WHERE id = :id")
                .param(
                        "id",
                        other.answerMessageId()
                )
                .update();
        UUID request = UUID.randomUUID();
        // when
        SearchQuestionAcceptedResult next = accept(
                request,
                "후속"
        );
        SearchQuestionAcceptedResult otherNext = intake.acceptNext(
                workspace,
                member,
                other.conversationId(),
                command(
                        request,
                        "다른 후속"
                )
        );
        // then
        assertThat(otherNext.conversationId()).isNotEqualTo(next.conversationId());
        assertThat(count("search_messages")).isEqualTo(8);
    }

    @Test
    @DisplayName("마지막 메시지가 질문이면 깨진 대화에 추가하지 않는다")
    void acceptNext_failure_invalidLastRole() {
        // given
        jdbc.sql("UPDATE search_messages SET role = 'USER', status = 'RECEIVED' WHERE id = :id")
                .param(
                        "id",
                        first.answerMessageId()
                )
                .update();
        // when & then
        assertThatThrownBy(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                )
        ).isInstanceOf(SearchException.class);
        assertUnchanged();
    }

    @Test
    @DisplayName("sequence 범위를 넘으면 기존 순서를 재사용하지 않는다")
    void acceptNext_failure_sequenceOverflow() {
        // given
        jdbc.sql("UPDATE search_messages SET sequence = 2147483646 WHERE id = :id")
                .param(
                        "id",
                        first.answerMessageId()
                )
                .update();
        // when & then
        assertThatThrownBy(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                )
        ).isInstanceOf(SearchException.class);
        assertUnchanged();
    }

    private SearchQuestionAcceptedResult accept(
            UUID request,
            String content
    ) {
        return intake.acceptNext(
                workspace,
                member,
                first.conversationId(),
                command(
                        request,
                        content
                )
        );
    }

    @Test
    @DisplayName("빈 저장 대화에 임의의 첫 sequence로 후속 질문을 추가하지 않는다")
    void acceptNext_failure_emptyConversation() {
        // given
        long empty = new SearchFixtures(jdbc).saveEmptyConversation(
                workspace,
                member,
                Instant.now()
        );
        // when & then
        assertThatThrownBy(
                () -> intake.acceptNext(
                        workspace,
                        member,
                        empty,
                        command(
                                UUID.randomUUID(),
                                "후속"
                        )
                )
        ).isInstanceOf(SearchException.class);
        assertUnchanged();
    }

    @Test
    @DisplayName("receipt 저장 실패도 새 메시지와 실행 작업을 롤백한다")
    void acceptNext_failure_receiptRollback() {
        // given
        Instant updated = time("updated_at");
        doThrow(new IllegalStateException("접수 기록 저장 실패")).when(repository)
                .save(any(SearchRequestReceipt.class));
        // when & then
        assertThatThrownBy(
                () -> accept(
                        UUID.randomUUID(),
                        "후속"
                )
        ).hasCauseInstanceOf(IllegalStateException.class);
        assertUnchanged();
        assertThat(time("updated_at")).isEqualTo(updated);
    }

    @Test
    @DisplayName("중지와 새 질문 경합은 중지 확정 이후에만 다음 쌍을 만든다")
    void acceptNext_success_concurrentStop() throws Exception {
        // given
        finishFirst("STREAMING");
        CyclicBarrier start = new CyclicBarrier(2);
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> stopped = executor.submit(() -> {
                start.await();
                return executions.stop(
                        workspace,
                        member,
                        first.conversationId(),
                        first.answerMessageId()
                );
            });
            Future<String> question = executor.submit(
                    () -> attempt(
                            start,
                            UUID.randomUUID(),
                            "중지 뒤 질문"
                    )
            );
            stopped.get(
                    10,
                    TimeUnit.SECONDS
            );
            String result = question.get(
                    10,
                    TimeUnit.SECONDS
            );
            // then
            assertThat(result).isIn(
                    "ACCEPTED",
                    "ANSWER_ALREADY_IN_PROGRESS"
            );
            if (result.equals("ANSWER_ALREADY_IN_PROGRESS")) {
                assertUnchanged();
                accept(
                        UUID.randomUUID(),
                        "중지 뒤 질문"
                );
            }
        }
        assertThat(count("search_messages")).isEqualTo(4);
        assertThat(repository.findMessage(first.answerMessageId())).get()
                .extracting(
                        message -> message.getStatus()
                                .name()
                )
                .isEqualTo("STOPPED");
    }

    private SearchQuestionCommand command(
            UUID request,
            String content
    ) {
        return new SearchQuestionCommand(
                content,
                request
        );
    }

    private void finishFirst(String status) {
        jdbc.sql("UPDATE search_messages SET status = :status WHERE id = :id")
                .param(
                        "status",
                        status
                )
                .param(
                        "id",
                        first.answerMessageId()
                )
                .update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private Instant time(String column) {
        return jdbc.sql("SELECT " + column + " FROM search_conversations WHERE id = :id")
                .param(
                        "id",
                        first.conversationId()
                )
                .query(Instant.class)
                .single();
    }

    private void assertUnchanged() {
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(1);
        assertThat(count("search_answer_executions")).isEqualTo(1);
    }

    private void assertCode(
            Runnable action,
            String code
    ) {
        assertThatThrownBy(action::run).isInstanceOf(SearchException.class)
                .satisfies(
                        error -> assertThat(
                                ((SearchException) error).getErrorCode()
                                        .getCode()
                        ).isEqualTo(code)
                );
    }

    private String attempt(
            CyclicBarrier start,
            UUID request,
            String content
    ) throws Exception {
        start.await();
        try {
            accept(
                    request,
                    content
            );
            return "ACCEPTED";
        } catch (SearchException exception) {
            return exception.getErrorCode()
                    .getCode();
        }
    }
}
