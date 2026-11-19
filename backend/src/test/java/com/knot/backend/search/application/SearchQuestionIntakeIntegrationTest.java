package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import static org.mockito.Mockito.when;
import com.knot.backend.search.domain.model.SearchIntent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.repository.SearchRepository;
import com.knot.backend.search.domain.model.SearchAnswerExecution;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
class SearchQuestionIntakeIntegrationTest {
    private static final UUID REQUEST_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    @Autowired
    private SearchQuestionIntakeService intake;
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
    private long workspaceId;
    private long memberId;

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
        memberId = fixtures.saveMember("질문자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("접수 커밋은 개인 대화와 순서 있는 질문·답변 및 영속 실행 의도를 함께 남긴다")
    void acceptFirst_success_atomicCommit() {
        // when
        SearchQuestionAcceptedResult accepted = accept(" 질문 😀 ");
        // then
        assertThat(count("search_conversations")).isEqualTo(1);
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(1);
        assertThat(count("search_answer_executions")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                accepted.questionMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(" 질문 😀 ");
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                accepted.answerMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("STREAMING");
        assertThat(
                jdbc.sql("SELECT state FROM search_answer_executions")
                        .query(String.class)
                        .single()
        ).isEqualTo("QUEUED");
        verifyNoInteractions(
                retriever,
                generator
        );
    }

    @Test
    @DisplayName("같은 본문 재접수는 기존 세 ID를 돌려주고 추가 작업을 만들지 않는다")
    void acceptFirst_success_repeatedRequest() {
        SearchQuestionAcceptedResult first = accept("질문");
        assertThat(accept("질문")).isEqualTo(first);
        assertThat(count("search_conversations")).isEqualTo(1);
        assertThat(count("search_messages")).isEqualTo(2);
    }

    @Test
    @DisplayName("본문 공백 차이도 requestId 충돌로 거절한다")
    void acceptFirst_failure_requestIdConflict() {
        accept("질문");
        assertThatThrownBy(() -> accept(" 질문")).isInstanceOf(SearchException.class)
                .satisfies(
                        error -> assertThat(
                                ((SearchException) error).getErrorCode()
                                        .getCode()
                        ).isEqualTo("REQUEST_ID_CONFLICT")
                );
        assertThat(count("search_conversations")).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 같은 요청을 접수해도 한 건의 대화와 같은 ID를 반환한다")
    void acceptFirst_success_concurrentDuplicate() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<SearchQuestionAcceptedResult> first = executor.submit(() -> {
                start.await();
                return accept("질문");
            });
            java.util.concurrent.Future<SearchQuestionAcceptedResult> second = executor.submit(() -> {
                start.await();
                return accept("질문");
            });
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(count("search_conversations")).isEqualTo(1);
        assertThat(count("search_answer_executions")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키의 다른 본문 동시 접수는 한 건만 접수한다")
    void acceptFirst_failure_concurrentConflict() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Integer> first = executor.submit(
                    () -> attempt(
                            start,
                            "첫 질문"
                    )
            );
            java.util.concurrent.Future<Integer> second = executor.submit(
                    () -> attempt(
                            start,
                            "다른 질문"
                    )
            );
            assertThat(first.get() + second.get()).isEqualTo(1);
        }
        assertThat(count("search_conversations")).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 의도 저장 실패는 대화·질문·답변·receipt까지 전부 롤백한다")
    void acceptFirst_failure_executionSaveRollback() {
        doThrow(new IllegalStateException("저장 실패")).when(repository)
                .save(any(SearchAnswerExecution.class));
        assertThatThrownBy(() -> accept("질문"))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertEmpty();
    }

    @Test
    @DisplayName("다른 멤버와 Workspace는 동일 UUID를 독립적으로 사용할 수 있다")
    void acceptFirst_success_separateScopes() {
        accept("질문");
        long otherMember = fixtures.saveMember("다른 멤버");
        fixtures.join(
                workspaceId,
                otherMember
        );
        long otherWorkspace = fixtures.saveWorkspace();
        fixtures.join(
                otherWorkspace,
                memberId
        );
        intake.acceptFirst(
                workspaceId,
                otherMember,
                new SearchQuestionCommand(
                        "다른 질문",
                        REQUEST_ID
                )
        );
        intake.acceptFirst(
                otherWorkspace,
                memberId,
                new SearchQuestionCommand(
                        "다른 질문",
                        REQUEST_ID
                )
        );
        assertThat(count("search_conversations")).isEqualTo(3);
    }

    @Test
    @DisplayName("탈퇴한 멤버는 재접수도 접근할 수 없다")
    void acceptFirst_failure_leftMember() {
        accept("질문");
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        assertThatThrownBy(() -> accept("질문")).hasMessageContaining("접근");
        assertThat(count("search_conversations")).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 준비가 안 되면 접수 전 실패로 어떤 탐색 데이터도 만들지 않는다")
    void acceptFirst_failure_unavailable() {
        doThrow(new SearchException(com.knot.backend.search.domain.SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE))
                .when(availability)
                .requireAvailable();
        assertThatThrownBy(() -> accept("질문")).isInstanceOf(SearchException.class);
        assertEmpty();
    }

    @Test
    @DisplayName("receipt UUID 범위 UNIQUE와 실행 메시지 FK는 DB에서 강제된다")
    void save_failure_databaseConstraints() {
        accept("질문");
        assertThatThrownBy(
                () -> jdbc.sql("""
                        INSERT INTO search_request_receipts
                            (workspace_id, member_id, operation, target_id, request_id, conversation_id,
                             question_message_id, answer_message_id, created_at)
                        SELECT workspace_id, member_id, operation, target_id, request_id, conversation_id,
                               question_message_id, answer_message_id, created_at FROM search_request_receipts
                        """)
                        .update()
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                () -> jdbc.sql("DELETE FROM search_messages")
                        .update()
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private SearchQuestionAcceptedResult accept(String content) {
        return intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        content,
                        REQUEST_ID
                )
        );
    }

    private int attempt(
            CyclicBarrier barrier,
            String content
    ) throws Exception {
        barrier.await();
        try {
            accept(content);
            return 1;
        } catch (SearchException exception) {
            assertThat(
                    exception.getErrorCode()
                            .getCode()
            ).isEqualTo("REQUEST_ID_CONFLICT");
            return 0;
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private void assertEmpty() {
        assertThat(count("search_conversations")).isZero();
        assertThat(count("search_messages")).isZero();
        assertThat(count("search_request_receipts")).isZero();
        assertThat(count("search_answer_executions")).isZero();
    }
}
