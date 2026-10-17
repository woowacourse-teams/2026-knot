package com.knot.backend.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.infrastructure.persistence.DocumentConfirmationQueryAdapter;
import com.knot.backend.document.infrastructure.persistence.DocumentListQueryAdapter;
import com.knot.backend.recording.infrastructure.persistence.TranscriptSegmentRepositoryAdapter;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.dto.query.SearchMessageListParameters;
import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.search.application.dto.result.SearchMessageListResult;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.infrastructure.persistence.SearchConversationListQueryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchMessageListQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@MockitoSpyBean(types = {DocumentListQueryAdapter.class, DocumentConfirmationQueryAdapter.class,
        TranscriptSegmentRepositoryAdapter.class, SearchConversationListQueryAdapter.class,
        SearchMessageListQueryAdapter.class})
class SearchMessageListSnapshotIntegrationTest {

    @Autowired
    private SearchMessageListService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SearchMessageListQueryAdapter query;
    private SearchMessageFixtures messages;
    private long workspaceId;
    private long memberId;
    private long conversationId;
    private long answerId;
    private long oldDocumentId;
    private long newDocumentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures documents = new DocumentFixtures(jdbc);
        SearchFixtures search = new SearchFixtures(jdbc);
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
                "이전 부분 답변"
        );
        answerId = messages.messageId(
                conversationId,
                2
        );
        oldDocumentId = messages.saveDocument(
                workspaceId,
                memberId
        );
        newDocumentId = messages.saveDocument(
                workspaceId,
                memberId
        );
        messages.saveEvidence(
                answerId,
                oldDocumentId,
                1
        );
    }

    @Test
    @DisplayName("메시지와 근거 사이에 생성 결과가 commit되어도 같은 snapshot을 반환한다")
    void find_success_concurrentAnswerAndEvidenceCommit() {
        // given
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (committed.compareAndSet(
                    false,
                    true
            )) {
                commitAnswerInOtherTransaction();
            }
            return invocation.callRealMethod();
        }).when(query)
                .findEvidences(
                        eq(workspaceId),
                        eq(memberId),
                        eq(conversationId),
                        anyList()
                );

        // when
        SearchMessageListResult first = findMessages();

        // then
        SearchMessageListItemResult oldAnswer = first.items()
                .getLast();
        assertThat(oldAnswer.content()).isEqualTo("이전 부분 답변");
        assertThat(oldAnswer.status()).isEqualTo(SearchMessageStatus.STREAMING);
        assertThat(oldAnswer.evidences()).extracting(SearchEvidenceItemResult::documentId)
                .containsExactly(oldDocumentId);
    }

    @Test
    @DisplayName("별도 트랜잭션에서 완료된 결과는 다음 요청의 snapshot에 반영한다")
    void find_success_snapshotAfterAnswerCommit() throws Exception {
        // given
        commitAnswerInOtherTransaction();
        // when
        SearchMessageListResult next = findMessages();
        // then
        SearchMessageListItemResult newAnswer = next.items()
                .getLast();
        assertThat(newAnswer.content()).isEqualTo("완료 답변");
        assertThat(newAnswer.status()).isEqualTo(SearchMessageStatus.COMPLETED);
        assertThat(newAnswer.evidences()).extracting(SearchEvidenceItemResult::documentId)
                .containsExactly(newDocumentId);
    }

    private SearchMessageListResult findMessages() {
        return service.find(
                workspaceId,
                memberId,
                conversationId,
                SearchMessageListParameters.of(
                        null,
                        null
                )
        );
    }

    private void commitAnswerInOtherTransaction() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.sql("UPDATE search_messages SET content = :content, status = 'COMPLETED' WHERE id = :id")
                        .param(
                                "content",
                                "완료 답변"
                        )
                        .param(
                                "id",
                                answerId
                        )
                        .update();
                jdbc.sql("DELETE FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .update();
                messages.saveEvidence(
                        answerId,
                        newDocumentId,
                        1
                );
            }))
                    .get(
                            10,
                            TimeUnit.SECONDS
                    );
        }
    }
}
