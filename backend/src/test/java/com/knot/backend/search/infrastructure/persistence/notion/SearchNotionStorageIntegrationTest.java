package com.knot.backend.search.infrastructure.persistence.notion;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDraft;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardOrigin;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({TestcontainersConfiguration.class, NotionConnectionRepositoryAdapter.class,
        SearchWriteCardRepositoryAdapter.class, SearchNotionAnswerRepositoryAdapter.class})
class SearchNotionStorageIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private NotionConnectionRepository connections;
    @Autowired
    private SearchWriteCardRepository cards;
    @Autowired
    private SearchNotionAnswerRepository answers;
    private long workspaceId;
    private long memberId;
    private long conversationId;
    private long answerId;
    private long documentId;

    @BeforeEach
    void setUp() {
        DocumentFixtures base = new DocumentFixtures(jdbc);
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        workspaceId = base.saveWorkspace();
        memberId = base.saveMember("탐색자");
        conversationId = new SearchFixtures(jdbc).saveConversation(
                workspaceId,
                memberId,
                "회의록 노션에 만들어 줘",
                "확인해 주세요"
        );
        answerId = messages.messageId(
                conversationId,
                2
        );
        documentId = messages.saveDocument(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("노션 연결은 Workspace마다 하나를 저장하고 다시 읽는다")
    void connection_roundTrip() {
        // given
        connections.save(
                NotionConnection.connect(
                        workspaceId,
                        memberId,
                        new NotionConnectionGrant(
                                "notion-workspace",
                                "Knot 팀",
                                "🪢",
                                "encrypted-access",
                                "encrypted-refresh",
                                NOW.plusSeconds(3600)
                        ),
                        NOW
                )
        );
        connections.flush();
        entityManager.clear();

        // when
        NotionConnection found = connections.findByWorkspaceIdForUpdate(workspaceId)
                .orElseThrow();

        // then
        assertThat(found.isConnectedBy(memberId)).isTrue();
        assertThat(found.getNotionWorkspaceName()).isEqualTo("Knot 팀");
    }

    @Test
    @DisplayName("쓰기 확인 카드는 DB 칸 순서와 만든 페이지 이력과 함께 저장된다")
    void card_roundTrip() {
        // given
        SearchWriteCard card = cards.save(databaseCard());
        card.start(NOW);
        card.complete(
                "https://www.notion.so/created",
                NOW
        );
        cards.save(
                SearchCreatedNotionPage.record(
                        card,
                        "created-page-id",
                        "https://www.notion.so/created",
                        NOW
                )
        );
        cards.flush();
        entityManager.clear();

        // when
        SearchWriteCard found = cards.findByAnswerMessageIds(List.of(answerId))
                .getFirst();
        List<SearchCreatedNotionPage> history = cards.findCreatedPages(
                conversationId,
                documentId
        );

        // then
        assertThat(found.getStatus()).isEqualTo(SearchWriteCardStatus.DONE);
        assertThat(found.getDatabaseFields()).extracting(SearchWriteCardDatabaseField::name)
                .containsExactly(
                        "이름",
                        "날짜"
                );
        assertThat(history).extracting(SearchCreatedNotionPage::getUrl)
                .containsExactly("https://www.notion.so/created");
    }

    @Test
    @DisplayName("대화의 대기 카드만 잠가서 찾는다")
    void pendingCards_conversation() {
        // given
        SearchWriteCard card = cards.save(databaseCard());
        cards.flush();

        // when
        List<SearchWriteCard> pending = cards.findPendingByConversationIdForUpdate(conversationId);

        // then
        assertThat(pending).extracting(SearchWriteCard::getId)
                .containsExactly(card.getId());
    }

    @Test
    @DisplayName("노션 근거와 안내 사유를 답변별로 저장하고 지운다")
    void answer_roundTrip() {
        // given
        answers.save(
                SearchNotionEvidence.create(
                        answerId,
                        1,
                        "출시 계획",
                        "https://www.notion.so/plan"
                )
        );
        answers.save(
                SearchNotionNotice.create(
                        answerId,
                        SearchNotionNoticeReason.READ_FAILED
                )
        );
        entityManager.flush();
        entityManager.clear();

        // when
        List<SearchNotionEvidence> evidences = answers.findEvidences(List.of(answerId));
        List<SearchNotionNotice> notices = answers.findNotices(List.of(answerId));
        answers.deleteByMessageId(answerId);
        entityManager.flush();

        // then
        assertThat(evidences).extracting(SearchNotionEvidence::getUrl)
                .containsExactly("https://www.notion.so/plan");
        assertThat(notices).extracting(SearchNotionNotice::getReason)
                .containsExactly(SearchNotionNoticeReason.READ_FAILED);
        assertThat(answers.findEvidences(List.of(answerId))).isEmpty();
    }

    private SearchWriteCard databaseCard() {
        return SearchWriteCard.createNotionPage(
                new SearchWriteCardOrigin(
                        answerId,
                        conversationId,
                        workspaceId,
                        memberId
                ),
                "notion-workspace",
                new SearchWriteCardDraft(
                        SearchWriteCardLocationKind.DATABASE,
                        "data-source-id",
                        "회의록",
                        "팀 위키 / 회의록",
                        "주간 회의",
                        "## 결정",
                        List.of(
                                new SearchWriteCardDatabaseField(
                                        "이름",
                                        "주간 회의",
                                        "title"
                                ),
                                new SearchWriteCardDatabaseField(
                                        "날짜",
                                        "2026-11-13",
                                        "date"
                                )
                        ),
                        documentId,
                        "주간 회의",
                        LocalDate.parse("2026-11-13"),
                        true
                ),
                NOW
        );
    }
}
