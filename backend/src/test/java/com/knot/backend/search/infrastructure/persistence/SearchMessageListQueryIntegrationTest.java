package com.knot.backend.search.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.domain.model.SearchEvidence;
import com.knot.backend.search.domain.model.SearchEvidenceId;
import com.knot.backend.search.domain.model.SearchMessage;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({TestcontainersConfiguration.class, SearchMessageListQueryAdapter.class, SearchDocumentListStore.class})
class SearchMessageListQueryIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entities;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private SearchMessageListQueryAdapter query;
    private DocumentFixtures documents;
    private SearchFixtures search;
    private SearchMessageFixtures messages;
    private long workspaceId;
    private long memberId;
    private long conversationId;

    @BeforeEach
    void setUp() {
        documents = new DocumentFixtures(jdbc);
        search = new SearchFixtures(jdbc);
        messages = new SearchMessageFixtures(jdbc);
        workspaceId = documents.saveWorkspace();
        memberId = documents.saveMember("조회자");
        documents.join(
                workspaceId,
                memberId
        );
        conversationId = search.saveConversation(
                workspaceId,
                memberId,
                "질문",
                "답변"
        );
    }

    @Test
    @DisplayName("같은 페이지의 여러 답변에 각각 상위 세 카드를 반환한다")
    void findEvidences_success_multipleAnswersInOnePage() {
        search.saveMessage(
                conversationId,
                "USER",
                3,
                "후속 질문",
                "RECEIVED"
        );
        search.saveMessage(
                conversationId,
                "ASSISTANT",
                4,
                "후속 답변",
                "COMPLETED"
        );
        long first = messages.messageId(
                conversationId,
                2
        );
        long second = messages.messageId(
                conversationId,
                4
        );
        for (int rank = 1; rank <= 5; rank++) {
            messages.saveEvidence(
                    first,
                    messages.saveDocument(
                            workspaceId,
                            memberId
                    ),
                    rank
            );
            messages.saveEvidence(
                    second,
                    messages.saveDocument(
                            workspaceId,
                            memberId
                    ),
                    rank
            );
        }
        Map<Long, List<SearchEvidenceItemResult>> result = query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                List.of(
                        first,
                        second
                )
        );
        assertThat(result.get(first)).extracting(SearchEvidenceItemResult::rank)
                .containsExactly(
                        1,
                        2,
                        3
                );
        assertThat(result.get(second)).extracting(SearchEvidenceItemResult::rank)
                .containsExactly(
                        1,
                        2,
                        3
                );
    }

    @Test
    @DisplayName("실제 근거는 모두 보존하고 각 답변의 관련도 상위 세 카드만 조회한다")
    void findEvidences_success_threeCardsPerAnswer() {
        long firstAnswer = messages.messageId(
                conversationId,
                2
        );
        long secondConversation = search.saveConversation(
                workspaceId,
                memberId,
                "후속 질문",
                "답변"
        );
        long secondAnswer = messages.messageId(
                secondConversation,
                2
        );
        for (int rank = 1; rank <= 5; rank++) {
            messages.saveEvidence(
                    firstAnswer,
                    messages.saveDocument(
                            workspaceId,
                            memberId
                    ),
                    rank
            );
            messages.saveEvidence(
                    secondAnswer,
                    messages.saveDocument(
                            workspaceId,
                            memberId
                    ),
                    rank
            );
        }
        Map<Long, List<SearchEvidenceItemResult>> first = query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                List.of(firstAnswer)
        );
        Map<Long, List<SearchEvidenceItemResult>> second = query.findEvidences(
                workspaceId,
                memberId,
                secondConversation,
                List.of(secondAnswer)
        );
        assertThat(first.get(firstAnswer)).extracting(SearchEvidenceItemResult::rank)
                .containsExactly(
                        1,
                        2,
                        3
                );
        assertThat(second.get(secondAnswer)).extracting(SearchEvidenceItemResult::rank)
                .containsExactly(
                        1,
                        2,
                        3
                );
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_evidences WHERE message_id IN (:ids)")
                        .param(
                                "ids",
                                List.of(
                                        firstAnswer,
                                        secondAnswer
                                )
                        )
                        .query(Long.class)
                        .single()
        ).isEqualTo(10);
    }

    @Test
    @DisplayName("목록에서 숨긴 대화도 단건 조회한다")
    void findConversation_success_hiddenConversation() {
        // given
        search.setVisible(
                conversationId,
                false
        );
        // when & then
        assertThat(query.findConversation(conversationId)).isPresent();
    }

    @Test
    @DisplayName("존재하지 않는 대화는 비어 있다")
    void findConversation_failure_missingConversation() {
        // when & then
        assertThat(query.findConversation(Long.MAX_VALUE)).isEmpty();
    }

    @ParameterizedTest(name = "beforeSequence={0}, size={1}")
    @MethodSource("messagePageCases")
    @DisplayName("최신·과거·빈 페이지의 순서 경계를 각각 조회한다")
    void findPage_success(
            Integer beforeSequence,
            int size,
            List<Integer> expected
    ) {
        // given
        search.saveMessage(
                conversationId,
                "USER",
                3,
                "새 질문",
                "RECEIVED"
        );
        search.saveMessage(
                conversationId,
                "ASSISTANT",
                4,
                "",
                "STREAMING"
        );
        // when
        List<SearchMessage> result = query.findPage(
                workspaceId,
                memberId,
                conversationId,
                beforeSequence,
                size
        );
        // then
        assertThat(result).extracting(SearchMessage::getSequence)
                .containsExactlyElementsOf(expected);
        if (beforeSequence == null) {
            SearchMessage latest = result.getFirst();
            assertThat(latest.getContent()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "member", "conversation"})
    @DisplayName("메시지 조회 SQL의 Workspace·소유자·대화 범위를 각각 검사한다")
    void findPage_failure_wrongScope(String target) {
        // given
        long requestedWorkspace = scopedId(
                target,
                "workspace",
                workspaceId
        );
        long requestedMember = scopedId(
                target,
                "member",
                memberId
        );
        long requestedConversation = scopedId(
                target,
                "conversation",
                conversationId
        );
        // when & then
        assertThat(
                query.findPage(
                        requestedWorkspace,
                        requestedMember,
                        requestedConversation,
                        null,
                        30
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("한 페이지의 답변별 세 근거를 한 SQL로 순위대로 복구한다")
    void findEvidences_success_bulkAndMapping() {
        // given
        long answer = messages.messageId(
                conversationId,
                2
        );
        long third = messages.saveDocument(
                workspaceId,
                memberId
        );
        long first = messages.saveDocument(
                workspaceId,
                memberId
        );
        long second = messages.saveDocument(
                workspaceId,
                memberId
        );
        messages.saveEvidence(
                answer,
                third,
                3
        );
        messages.saveEvidence(
                answer,
                first,
                1
        );
        messages.saveEvidence(
                answer,
                second,
                2
        );
        Statistics statistics = statistics();
        statistics.clear();
        // when
        Map<Long, List<SearchEvidenceItemResult>> result = query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                List.of(answer)
        );
        // then
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(result.get(answer)).extracting(SearchEvidenceItemResult::documentId)
                .containsExactly(
                        first,
                        second,
                        third
                );
        assertThat(result.get(answer)).extracting(SearchEvidenceItemResult::rank)
                .containsExactly(
                        1,
                        2,
                        3
                );
        assertThat(result.get(answer)).extracting(SearchEvidenceItemResult::title)
                .containsOnly("문서 보관 정책");
        assertThat(result.get(answer)).extracting(SearchEvidenceItemResult::topic)
                .containsOnly("탐색 테스트");
        SearchEvidence stored = entities
                .createQuery(
                        "select e from SearchEvidence e where e.id.messageId = :message and e.rank = 1",
                        SearchEvidence.class
                )
                .setParameter(
                        "message",
                        answer
                )
                .getSingleResult();
        assertThat(stored.getRank()).isEqualTo((short) 1);
        SearchEvidenceId storedId = stored.getId();
        assertThat(storedId.getMessageId()).isEqualTo(answer);
        assertThat(storedId.getDocumentId()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("여러 답변의 근거와 페이지 밖 근거를 구분하고 SQL 수는 늘지 않는다")
    void findEvidences_success_multipleAnswers(boolean oneAnswer) {
        // given
        search.saveMessage(
                conversationId,
                "USER",
                3,
                "후속",
                "RECEIVED"
        );
        search.saveMessage(
                conversationId,
                "ASSISTANT",
                4,
                "다음",
                "COMPLETED"
        );
        long firstAnswer = messages.messageId(
                conversationId,
                2
        );
        long nextAnswer = messages.messageId(
                conversationId,
                4
        );
        long firstDocument = messages.saveDocument(
                workspaceId,
                memberId
        );
        long nextDocument = messages.saveDocument(
                workspaceId,
                memberId
        );
        messages.saveEvidence(
                firstAnswer,
                firstDocument,
                1
        );
        messages.saveEvidence(
                nextAnswer,
                nextDocument,
                1
        );
        Statistics statistics = statistics();
        statistics.clear();
        List<Long> answerIds = List.of(
                firstAnswer,
                nextAnswer
        );
        if (oneAnswer) {
            answerIds = List.of(firstAnswer);
        }
        // when
        Map<Long, List<SearchEvidenceItemResult>> result = query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                answerIds
        );
        // then
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(answerIds);
    }

    @Test
    @DisplayName("잘못 저장된 USER 근거와 다른 Workspace 문서를 노출하지 않는다")
    void findEvidences_failure_invalidConnections() {
        // given
        long user = messages.messageId(
                conversationId,
                1
        );
        long answer = messages.messageId(
                conversationId,
                2
        );
        long local = messages.saveDocument(
                workspaceId,
                memberId
        );
        long foreign = messages.saveDocument(
                documents.saveWorkspace(),
                memberId
        );
        messages.saveEvidence(
                user,
                local,
                1
        );
        messages.saveEvidence(
                answer,
                foreign,
                1
        );
        // when & then
        assertThat(
                query.findEvidences(
                        workspaceId,
                        memberId,
                        conversationId,
                        List.of(
                                user,
                                answer
                        )
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 소유자가 전달한 정상 답변의 근거도 노출하지 않는다")
    void findEvidences_failure_wrongOwner() {
        // given
        long answer = messages.messageId(
                conversationId,
                2
        );
        long document = messages.saveDocument(
                workspaceId,
                memberId
        );
        messages.saveEvidence(
                answer,
                document,
                1
        );
        // when & then
        assertThat(
                query.findEvidences(
                        workspaceId,
                        Long.MAX_VALUE,
                        conversationId,
                        List.of(answer)
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 대화의 답변 ID를 전달해도 근거를 노출하지 않는다")
    void findEvidences_failure_otherConversation() {
        // given
        long other = search.saveConversation(
                workspaceId,
                memberId,
                "다른",
                "답변"
        );
        long answer = messages.messageId(
                other,
                2
        );
        messages.saveEvidence(
                answer,
                messages.saveDocument(
                        workspaceId,
                        memberId
                ),
                1
        );
        // when & then
        assertThat(
                query.findEvidences(
                        workspaceId,
                        memberId,
                        conversationId,
                        List.of(answer)
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("빈 답변 ID 목록은 SQL 없이 빈 근거를 반환한다")
    void findEvidencesEmpty_success() {
        // given
        Statistics statistics = statistics();
        statistics.clear();
        // when
        Map<Long, List<SearchEvidenceItemResult>> result = query.findEvidences(
                workspaceId,
                memberId,
                conversationId,
                List.of()
        );
        // then
        assertThat(result).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private static Stream<Arguments> messagePageCases() {
        return Stream.of(
                Arguments.of(
                        null,
                        2,
                        List.of(
                                4,
                                3,
                                2
                        )
                ),
                Arguments.of(
                        3,
                        2,
                        List.of(
                                2,
                                1
                        )
                ),
                Arguments.of(
                        1,
                        30,
                        List.of()
                )
        );
    }

    private long scopedId(
            String target,
            String field,
            long id
    ) {
        if (target.equals(field)) {
            return Long.MAX_VALUE;
        }
        return id;
    }

    private Statistics statistics() {
        SessionFactory sessions = entityManagerFactory.unwrap(SessionFactory.class);
        return sessions.getStatistics();
    }
}
