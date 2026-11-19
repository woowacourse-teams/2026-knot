package com.knot.backend.search.application.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerExecutionService;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.model.notion.NotionConnection;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class SearchNotionAnswerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private static final Instant MEETING_AT = Instant.parse("2026-11-18T16:00:00Z");

    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private NotionConnectionRepository connections;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchAnswerGenerator generator;
    @MockitoBean
    private SearchNotionAnswerGenerator notionGenerator;
    @MockitoBean
    private SearchNotionClient notion;
    @MockitoBean
    private Clock clock;
    private long workspaceId;
    private long memberId;
    private long documentId;
    private SearchQuestionAcceptedResult accepted;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("질문자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        documentId = new SearchMessageFixtures(jdbc).saveDocument(
                workspaceId,
                memberId
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
                .param(
                        "id",
                        documentId
                )
                .update();
        accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
        documents(
                List.of(
                        new SearchDocumentResult(
                                documentId,
                                "기획 회의",
                                "출시",
                                "출시일을 12월 8일로 정했다.",
                                MEETING_AT,
                                NOW
                        )
                )
        );
    }

    @Test
    @DisplayName("노션 읽기가 모두 끝난 뒤 작성 중으로 바꾸고 답변에 쓴 노션 페이지만 근거로 저장한다")
    void execute_success_notionEvidence() {
        connect();
        plan(
                Optional.of("출시 계획"),
                Optional.empty()
        );
        when(
                notion.search(
                        eq(workspaceId),
                        eq("출시 계획"),
                        any()
                )
        ).thenReturn(
                List.of(
                        new SearchNotionSearchHit(
                                "p1",
                                "출시 계획",
                                "https://notion.so/p1",
                                SearchNotionObjectKind.PAGE,
                                "",
                                ""
                        )
                )
        );
        doAnswer(call -> {
            assertThat(stage()).isEqualTo("SEARCHING");
            return new SearchNotionPage(
                    "p1",
                    "출시 계획",
                    "https://notion.so/p1",
                    "출시일 12월 8일"
            );
        }).when(notion)
                .fetchPage(
                        eq(workspaceId),
                        eq("p1"),
                        any()
                );
        doAnswer(call -> {
            assertThat(stage()).isEqualTo("GENERATING");
            List<SearchNotionPage> pages = call.getArgument(3);
            Consumer<String> delta = call.getArgument(5);
            delta.accept("노션과 회의 결정이 같아요.");
            return new SearchNotionAnswer(
                    List.of(documentId),
                    pages,
                    Optional.empty(),
                    Optional.empty()
            );
        }).when(notionGenerator)
                .generate(
                        any(),
                        eq("질문"),
                        any(),
                        any(),
                        eq(false),
                        any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo("노션과 회의 결정이 같아요.");
        assertThat(
                jdbc.sql("SELECT rank, title, url FROM search_notion_evidences")
                        .query()
                        .listOfRows()
        ).containsExactly(
                Map.of(
                        "rank",
                        1,
                        "title",
                        "출시 계획",
                        "url",
                        "https://notion.so/p1"
                )
        );
        assertThat(count("search_evidences")).isEqualTo(1);
        assertThat(count("search_notion_notices")).isZero();
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("노션 연결이 없으면 노션을 부르지 않고 회의 문서만으로 답한 뒤 연결 없음을 저장한다")
    void execute_success_notConnectedAnswersWithMeetings() {
        plan(
                Optional.of("출시 계획"),
                Optional.empty()
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("회의 문서로 답해요.");
            return List.of(documentId);
        }).when(generator)
                .generateFromRetrieval(
                        any(),
                        eq("질문"),
                        any(),
                        any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo("회의 문서로 답해요.");
        assertThat(notice()).isEqualTo("NOT_CONNECTED");
        assertThat(count("search_evidences")).isEqualTo(1);
        verifyNoInteractions(
                notion,
                notionGenerator
        );
    }

    @Test
    @DisplayName("만들기 요청은 답변 완료와 함께 대기 카드를 저장하고 다시 요청하면 앞선 대기 카드를 취소한다")
    void execute_success_createsCardAndCancelsPrevious() {
        connect();
        plan(
                Optional.empty(),
                Optional.of("회의록 DB")
        );
        databaseLocation();
        draft("11월 기획 회의");
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo("회의록을 노션 페이지로 만들 준비를 했어요.");
        Map<String, Object> card = jdbc.sql("""
                SELECT status, notion_workspace_id, location_kind, location_notion_id, location_name,
                       location_path, title, body, source_document_id, source_document_title,
                       meeting_date::text AS meeting_date, executable
                FROM search_write_cards
                """)
                .query()
                .singleRow();
        assertThat(card).containsEntry(
                "status",
                "PENDING"
        )
                .containsEntry(
                        "notion_workspace_id",
                        "notion-ws"
                )
                .containsEntry(
                        "location_kind",
                        "DATABASE"
                )
                .containsEntry(
                        "location_notion_id",
                        "collection-1"
                )
                .containsEntry(
                        "location_name",
                        "회의록"
                )
                .containsEntry(
                        "location_path",
                        "팀 위키 / 회의록"
                )
                .containsEntry(
                        "title",
                        "11월 기획 회의"
                )
                .containsEntry(
                        "body",
                        "## 결정\n- 출시일 12월 8일"
                )
                .containsEntry(
                        "source_document_id",
                        documentId
                )
                .containsEntry(
                        "source_document_title",
                        "기획 회의"
                )
                .containsEntry(
                        "meeting_date",
                        "2026-11-19"
                )
                .containsEntry(
                        "executable",
                        true
                );
        assertThat(
                jdbc.sql("SELECT name, value, property_type FROM search_write_card_database_fields ORDER BY position")
                        .query()
                        .listOfRows()
        ).containsExactly(
                Map.of(
                        "name",
                        "이름",
                        "value",
                        "11월 기획 회의",
                        "property_type",
                        "title"
                ),
                Map.of(
                        "name",
                        "회의 날짜",
                        "value",
                        "2026-11-19",
                        "property_type",
                        "date"
                )
        );
        SearchQuestionAcceptedResult next = intake.acceptNext(
                workspaceId,
                memberId,
                accepted.conversationId(),
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
        draft("제목을 바꾼 기획 회의");
        worker.execute(next.answerMessageId());
        assertThat(
                jdbc.sql("SELECT status FROM search_write_cards ORDER BY id")
                        .query(String.class)
                        .list()
        ).containsExactly(
                "CANCELED",
                "PENDING"
        );
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @EnumSource(value = SearchNotionWriteSkip.class, names = {"LOCATION_MISSING", "NOT_CONNECTED", "CONNECTION_EXPIRED",
            "MEETING_NOT_FOUND", "LOCATION_NOT_FOUND", "LOCATION_FAILED"})
    @DisplayName("카드를 만들지 않는 만들기 요청은 초안 없이 안내 답글로 완료하고 연결이 없거나 만료여도 노션 안내는 저장하지 않는다")
    void execute_success_writeSkippedWithGuidance(SearchNotionWriteSkip skip) {
        String location = "회의록";
        if (skip == SearchNotionWriteSkip.LOCATION_MISSING) {
            location = "";
        }
        if (skip != SearchNotionWriteSkip.NOT_CONNECTED) {
            connect();
        }
        if (skip == SearchNotionWriteSkip.CONNECTION_EXPIRED) {
            expireConnection();
        }
        if (skip == SearchNotionWriteSkip.MEETING_NOT_FOUND) {
            documents(List.of());
        }
        if (skip == SearchNotionWriteSkip.LOCATION_FAILED) {
            when(
                    notion.search(
                            anyLong(),
                            anyString(),
                            any()
                    )
            ).thenThrow(new SearchNotionException(SearchNotionFailure.TIMEOUT));
        }
        if (skip == SearchNotionWriteSkip.MEETING_NOT_FOUND) {
            pageLocation();
        }
        plan(
                Optional.empty(),
                Optional.of(location)
        );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo(skip.guidance());
        assertThat(count("search_write_cards")).isZero();
        assertThat(count("search_evidences")).isZero();
        assertThat(count("search_notion_notices")).isZero();
        verifyNoInteractions(
                generator,
                notionGenerator
        );
    }

    @Test
    @DisplayName("답변 AI가 여러 페이지 요청이라고 알리면 카드 없이 안내 답글을 붙인다")
    void execute_success_modelDraftIssue() {
        connect();
        plan(
                Optional.empty(),
                Optional.of("회의록")
        );
        pageLocation();
        doAnswer(
                call -> new SearchNotionAnswer(
                        List.of(),
                        List.of(),
                        Optional.empty(),
                        Optional.of(SearchNotionWriteSkip.MULTIPLE_PAGES)
                )
        ).when(notionGenerator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(true),
                        any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo(SearchNotionGuidance.MULTIPLE_PAGES);
        assertThat(count("search_write_cards")).isZero();
    }

    @Test
    @DisplayName("초안을 쓰는 중에 사용자가 답변을 멈추면 카드를 만들지 않는다")
    void execute_success_stoppedWhileDraftingCreatesNoCard() {
        connect();
        plan(
                Optional.empty(),
                Optional.of("회의록")
        );
        pageLocation();
        doAnswer(call -> {
            executions.stop(
                    workspaceId,
                    memberId,
                    accepted.conversationId(),
                    accepted.answerMessageId()
            );
            Consumer<String> delta = call.getArgument(5);
            delta.accept("멈춘 뒤 답글");
            return new SearchNotionAnswer(
                    List.of(documentId),
                    List.of(),
                    Optional.of(
                            new SearchNotionAnswer.Draft(
                                    "기획 회의",
                                    "본문",
                                    documentId
                            )
                    ),
                    Optional.empty()
            );
        }).when(notionGenerator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("STOPPED");
        assertThat(count("search_write_cards")).isZero();
        assertThat(count("search_notion_notices")).isZero();
    }

    private void plan(
            Optional<String> read,
            Optional<String> create
    ) {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchUsePlan(
                        SearchIntent.MEETING_SEARCH,
                        read,
                        create
                )
        );
    }

    private void documents(List<SearchDocumentResult> found) {
        when(
                retriever.retrieve(
                        any(SearchToolScope.class),
                        eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        found,
                        0
                )
        );
    }

    private void connect() {
        connections.save(
                NotionConnection.connect(
                        workspaceId,
                        memberId,
                        new NotionConnectionGrant(
                                "notion-ws",
                                "팀 노션",
                                null,
                                "encrypted-access",
                                null,
                                null
                        ),
                        NOW
                )
        );
    }

    private void expireConnection() {
        jdbc.sql("UPDATE search_notion_connections SET status = 'EXPIRED'")
                .update();
    }

    private void pageLocation() {
        when(
                notion.search(
                        eq(workspaceId),
                        eq("회의록"),
                        any()
                )
        ).thenReturn(
                List.of(
                        new SearchNotionSearchHit(
                                "page-1",
                                "회의록",
                                "https://notion.so/page-1",
                                SearchNotionObjectKind.PAGE,
                                "",
                                ""
                        )
                )
        );
    }

    private void databaseLocation() {
        when(
                notion.search(
                        eq(workspaceId),
                        eq("회의록 DB"),
                        any()
                )
        ).thenReturn(
                List.of(
                        new SearchNotionSearchHit(
                                "db-1",
                                "회의록",
                                "https://notion.so/db-1",
                                SearchNotionObjectKind.DATABASE,
                                "",
                                ""
                        )
                )
        );
        when(
                notion.fetchDatabase(
                        eq(workspaceId),
                        eq("db-1"),
                        any()
                )
        ).thenReturn(
                new SearchNotionDatabase(
                        "collection-1",
                        "회의록",
                        "팀 위키 / 회의록",
                        List.of(
                                new SearchWriteCardDatabaseFields.Property(
                                        "이름",
                                        "title"
                                ),
                                new SearchWriteCardDatabaseFields.Property(
                                        "회의 날짜",
                                        "date"
                                ),
                                new SearchWriteCardDatabaseFields.Property(
                                        "참석자",
                                        "people"
                                )
                        )
                )
        );
    }

    private void draft(String title) {
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(5);
            delta.accept("회의록을 노션 페이지로 만들 준비를 했어요.");
            return new SearchNotionAnswer(
                    List.of(documentId),
                    List.of(),
                    Optional.of(
                            new SearchNotionAnswer.Draft(
                                    title,
                                    "## 결정\n- 출시일 12월 8일",
                                    documentId
                            )
                    ),
                    Optional.empty()
            );
        }).when(notionGenerator)
                .generate(
                        any(),
                        eq("질문"),
                        any(),
                        eq(List.of()),
                        eq(true),
                        any()
                );
    }

    private String status() {
        return jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private String content() {
        return jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private String stage() {
        return jdbc.sql("SELECT stage FROM search_answer_executions WHERE answer_id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private String notice() {
        return jdbc.sql("SELECT reason FROM search_notion_notices WHERE message_id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
