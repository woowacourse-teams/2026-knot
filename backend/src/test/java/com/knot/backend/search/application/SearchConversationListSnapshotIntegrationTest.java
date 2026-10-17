package com.knot.backend.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.infrastructure.persistence.DocumentConfirmationQueryAdapter;
import com.knot.backend.document.infrastructure.persistence.DocumentListQueryAdapter;
import com.knot.backend.recording.infrastructure.persistence.TranscriptSegmentRepositoryAdapter;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.application.dto.query.SearchConversationListParameters;
import com.knot.backend.search.infrastructure.persistence.SearchConversationListQueryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchMessageListQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class SearchConversationListSnapshotIntegrationTest {

    @Autowired
    private SearchConversationListService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SearchConversationListQueryAdapter query;
    private SearchFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long conversationId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures members = new DocumentFixtures(jdbc);
        fixtures = new SearchFixtures(jdbc);
        workspaceId = members.saveWorkspace();
        memberId = members.saveMember("조회자");
        members.join(
                workspaceId,
                memberId
        );
        conversationId = fixtures.saveConversation(
                workspaceId,
                memberId,
                "첫 질문",
                "이전 답변"
        );
    }

    @Test
    @DisplayName("권한 확인 후 메시지가 저장되어도 한 응답은 같은 DB 스냅샷을 읽는다")
    void find_success_keepsSnapshotEstablishedByMembershipReadDuringConcurrentMessageCommit() {
        doAnswer(invocation -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    fixtures.saveMessage(
                            conversationId,
                            "USER",
                            3,
                            "새 질문",
                            "RECEIVED"
                    );
                    fixtures.saveMessage(
                            conversationId,
                            "ASSISTANT",
                            4,
                            "새 답변",
                            "COMPLETED"
                    );
                    fixtures.setUpdatedAt(
                            conversationId,
                            SearchFixtures.TIME.plusSeconds(60)
                    );
                }))
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
            return invocation.callRealMethod();
        }).when(query)
                .findPage(
                        eq(workspaceId),
                        eq(memberId),
                        eq(20),
                        isNull()
                );

        var result = service.find(
                workspaceId,
                memberId,
                SearchConversationListParameters.of(
                        null,
                        null
                )
        );

        assertThat(result.items()).hasSize(1);
        assertThat(
                result.items()
                        .getFirst()
                        .lastMessagePreview()
        ).isEqualTo("이전 답변");
        assertThat(
                result.items()
                        .getFirst()
                        .updatedAt()
        ).isEqualTo(SearchFixtures.TIME);
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_messages WHERE conversation_id = :id")
                        .param(
                                "id",
                                conversationId
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(4);
    }
}
