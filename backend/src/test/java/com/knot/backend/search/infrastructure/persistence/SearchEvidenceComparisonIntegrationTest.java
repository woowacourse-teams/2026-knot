package com.knot.backend.search.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerContentPolicy;
import com.knot.backend.search.application.SearchMessageListService;
import com.knot.backend.search.application.dto.query.SearchMessageListParameters;
import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchEvidenceComparisonIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SearchMessageListService messages;
    @Autowired
    private PlatformTransactionManager transactions;
    @MockitoSpyBean
    private SearchEvidenceHistoryStore history;
    private long workspaceId;
    private long memberId;
    private long conversationId;
    private long answerId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures documents = new DocumentFixtures(jdbc);
        memberId = documents.saveMember("질문자");
        workspaceId = documents.saveWorkspace();
        documents.join(
                workspaceId,
                memberId
        );
        SearchFixtures search = new SearchFixtures(jdbc);
        conversationId = search.saveEmptyConversation(
                workspaceId,
                memberId,
                SearchFixtures.TIME
        );
        search.saveMessage(
                conversationId,
                "USER",
                1,
                "배포 일정은?",
                "RECEIVED"
        );
        search.saveMessage(
                conversationId,
                "ASSISTANT",
                2,
                "금요일 배포 예정이에요.",
                "COMPLETED"
        );
        SearchMessageFixtures fixtures = new SearchMessageFixtures(jdbc);
        answerId = fixtures.messageId(
                conversationId,
                2
        );
        documentId = fixtures.saveDocument(
                workspaceId,
                memberId
        );
        fixtures.saveEvidence(
                answerId,
                documentId,
                1,
                fixtures.sourceVersion(documentId)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"recovers", "twice", "permanent", "loses-access"})
    @DisplayName("실제 PostgreSQL 비교 오류는 새 트랜잭션에서 최대 한 번 재시도하고 접근을 다시 확인한다")
    void compareRetries(String scenario) {
        List<Long> transactionIds = new ArrayList<>();
        doAnswer(call -> {
            transactionIds.add(
                    jdbc.sql("SELECT txid_current()")
                            .query(Long.class)
                            .single()
            );
            if (scenario.equals("permanent")) {
                jdbc.sql("SELECT nonexistent_search_evidence_column")
                        .query()
                        .singleValue();
            }
            if (transactionIds.size() == 1 || scenario.equals("twice")) {
                jdbc.sql("DO $$ BEGIN RAISE EXCEPTION 'test serialization error' USING ERRCODE = '40001'; END $$")
                        .update();
            }
            if (scenario.equals("loses-access")) {
                TransactionTemplate mutation = new TransactionTemplate(transactions);
                mutation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                mutation.executeWithoutResult(
                        status -> jdbc.sql("DELETE FROM documents WHERE id=:id")
                                .param(
                                        "id",
                                        documentId
                                )
                                .update()
                );
            }
            return call.callRealMethod();
        }).when(history)
                .compare(
                        workspaceId,
                        memberId,
                        conversationId,
                        List.of(answerId)
                );

        SearchMessageListItemResult answer = messages.find(
                workspaceId,
                memberId,
                conversationId,
                SearchMessageListParameters.of(
                        null,
                        null
                )
        )
                .items()
                .getLast();

        int expectedCalls = scenario.equals("permanent") ? 1 : 2;
        verify(
                history,
                times(expectedCalls)
        ).compare(
                workspaceId,
                memberId,
                conversationId,
                List.of(answerId)
        );
        assertThat(transactionIds).hasSize(expectedCalls)
                .doesNotHaveDuplicates();
        if (scenario.equals("loses-access")) {
            assertThat(answer.content()).isEqualTo(SearchAnswerContentPolicy.SOURCE_UNAVAILABLE);
            assertThat(answer.evidences()).isEmpty();
        } else {
            String notice = scenario.equals("recovers") ? "" : "\n\n근거 문서의 변경 여부를 확인할 수 없어요.";
            assertThat(answer.content()).isEqualTo("금요일 배포 예정이에요." + notice);
            assertThat(answer.evidences()).hasSize(1);
        }
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id=:id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("금요일 배포 예정이에요.");
    }
}
