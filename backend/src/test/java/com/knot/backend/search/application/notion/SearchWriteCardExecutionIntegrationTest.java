package com.knot.backend.search.application.notion;

import org.springframework.context.ApplicationContext;
import java.time.Duration;
import com.knot.backend.search.infrastructure.SearchWriteCardRecoveryScheduler;
import com.knot.backend.global.config.SearchNotionProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.SearchNotionFixtures;
import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardOrigin;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
@Timeout(60)
class SearchWriteCardExecutionIntegrationTest {
    private static final int REQUESTS = 6;

    @Autowired
    private SearchWriteCardExecutionService executions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private NotionConnectionRepository connections;
    @Autowired
    private SearchWriteCardRepository cards;
    @Autowired
    private SearchNotionAnswerRepository answers;
    @Autowired
    private SearchWriteCardTransitionService transitions;
    @Autowired
    private SearchNotionProperties properties;
    @Autowired
    private ApplicationContext context;
    @MockitoBean
    private SearchNotionClient notion;
    private SearchNotionFixtures notionFixtures;
    private long workspaceId;
    private long memberId;
    private long conversationId;
    private SearchWriteCard card;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures documents = new DocumentFixtures(jdbc);
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        notionFixtures = new SearchNotionFixtures(
                jdbc,
                connections,
                cards,
                answers
        );
        memberId = documents.saveMember("질문자");
        workspaceId = documents.saveWorkspace();
        documents.join(
                workspaceId,
                memberId
        );
        conversationId = new SearchFixtures(jdbc).saveConversation(
                workspaceId,
                memberId,
                "회의록에 만들어 줘",
                "확인해 주세요"
        );
        notionFixtures.connect(
                workspaceId,
                memberId,
                SearchNotionFixtures.NOTION_WORKSPACE
        );
        card = notionFixtures.pageCard(
                new SearchWriteCardOrigin(
                        messages.messageId(
                                conversationId,
                                2
                        ),
                        conversationId,
                        workspaceId,
                        memberId
                ),
                messages.saveDocument(
                        workspaceId,
                        memberId
                )
        );
    }

    @Test
    @DisplayName("같은 카드에 동시에 온 실행 요청은 노션을 한 번만 부르고 모두 같은 DONE 결과를 받는다")
    void execute_success_concurrentRequestsCreateOnce() throws Exception {
        // given
        when(
                notion.createPage(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenAnswer(call -> {
            TimeUnit.MILLISECONDS.sleep(500);
            return new SearchNotionCreatedPage(
                    "created-page-id",
                    "https://www.notion.so/created"
            );
        });
        CyclicBarrier barrier = new CyclicBarrier(REQUESTS);
        List<Callable<SearchWriteCardResult>> requests = new ArrayList<>();
        for (int index = 0; index < REQUESTS; index++) {
            requests.add(() -> {
                barrier.await();
                return executions.execute(
                        workspaceId,
                        memberId,
                        conversationId,
                        card.getId()
                );
            });
        }

        // when
        List<SearchWriteCardResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            for (Future<SearchWriteCardResult> result : pool.invokeAll(requests)) {
                results.add(result.get());
            }
        }

        // then
        assertThat(results).hasSize(REQUESTS)
                .allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
        assertThat(
                results.getFirst()
                        .status()
        ).isEqualTo(SearchWriteCardStatus.DONE);
        assertThat(
                results.getFirst()
                        .createdPageUrl()
        ).isEqualTo("https://www.notion.so/created");
        verify(notion).createPage(
                anyLong(),
                any(),
                any()
        );
        assertThat(notionFixtures.createdPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("서버가 실행 중에 멈춰 RUNNING인 채 오래된 카드는 다음 실행 요청에서 무응답 실패로 끝낸다")
    void execute_failed_abandonedRunningCard() {
        // given
        jdbc.sql("UPDATE search_write_cards SET status = 'RUNNING', updated_at = :updatedAt WHERE id = :id")
                .param(
                        "updatedAt",
                        Timestamp.from(
                                Instant.now()
                                        .minusSeconds(3600)
                        )
                )
                .param(
                        "id",
                        card.getId()
                )
                .update();

        // when
        SearchWriteCardResult result = executions.execute(
                workspaceId,
                memberId,
                conversationId,
                card.getId()
        );

        // then
        assertThat(result.status()).isEqualTo(SearchWriteCardStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(SearchWriteCardFailureReason.NOTION_NO_RESPONSE);
        verifyNoInteractions(notion);
        assertThat(notionFixtures.createdPages()).isZero();
    }

    @Test
    @DisplayName("테스트 설정에서는 주기 정리 스케줄러를 띄우지 않는다")
    void recoveryScheduler_disabledInTests() {
        // when & then
        assertThat(context.getBeansOfType(SearchWriteCardRecoveryScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("주기 정리는 회수 기준보다 오래 실행 중인 카드만 무응답 실패로 바꾸고 아직 기준 안인 실행 중 카드는 그대로 둔다")
    void recoverAbandoned_onlyStaleRunningCards() {
        // given
        long otherConversation = new SearchFixtures(jdbc).saveConversation(
                workspaceId,
                memberId,
                "다른 회의록도 만들어 줘",
                "확인해 주세요"
        );
        SearchWriteCard live = notionFixtures.pageCard(
                new SearchWriteCardOrigin(
                        new SearchMessageFixtures(jdbc).messageId(
                                otherConversation,
                                2
                        ),
                        otherConversation,
                        workspaceId,
                        memberId
                ),
                card.getSourceDocumentId()
        );
        Duration abandonedAfter = properties.runningCardAbandonedAfter();
        markRunning(
                card.getId(),
                abandonedAfter.plusSeconds(1)
        );
        markRunning(
                live.getId(),
                abandonedAfter.minusSeconds(10)
        );

        // when
        new SearchWriteCardRecoveryScheduler(
                transitions,
                properties
        ).recoverAbandoned();

        // then
        assertThat(abandonedAfter).isEqualTo(Duration.ofSeconds(61));
        SearchWriteCard abandoned = cards.findById(card.getId())
                .orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(SearchWriteCardStatus.FAILED);
        assertThat(abandoned.getFailureReason()).isEqualTo(SearchWriteCardFailureReason.NOTION_NO_RESPONSE);
        assertThat(
                cards.findById(live.getId())
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(SearchWriteCardStatus.RUNNING);
        verifyNoInteractions(notion);
    }

    private void markRunning(
            long cardId,
            Duration age
    ) {
        jdbc.sql("UPDATE search_write_cards SET status = 'RUNNING', updated_at = :updatedAt WHERE id = :id")
                .param(
                        "updatedAt",
                        Timestamp.from(
                                Instant.now()
                                        .minus(age)
                        )
                )
                .param(
                        "id",
                        cardId
                )
                .update();
    }
}
