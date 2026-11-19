package com.knot.backend.search.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.SearchNotionFixtures;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.notion.SearchNotionClient;
import com.knot.backend.search.application.notion.SearchNotionCreatedPage;
import com.knot.backend.search.application.notion.SearchNotionDatabase;
import com.knot.backend.search.application.notion.SearchNotionException;
import com.knot.backend.search.application.notion.SearchNotionFailure;
import com.knot.backend.search.application.notion.SearchNotionPageCreation;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseFields;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardOrigin;
import com.knot.backend.search.domain.repository.notion.NotionConnectionRepository;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchWriteCardAcceptanceTest {
    private static final String BASE = "/api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}";
    private static final String CREATED_URL = "https://www.notion.so/created";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private NotionConnectionRepository connections;
    @Autowired
    private SearchWriteCardRepository cards;
    @Autowired
    private SearchNotionAnswerRepository answers;
    @Autowired
    private SearchQuestionIntakeService intake;
    @MockitoBean
    private SearchNotionClient notion;
    @MockitoBean
    private SearchGenerationAvailability availability;
    private DocumentFixtures documents;
    private SearchFixtures search;
    private SearchMessageFixtures messages;
    private SearchNotionFixtures notionFixtures;
    private long workspaceId;
    private long memberId;
    private long conversationId;
    private long answerId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        documents = new DocumentFixtures(jdbc);
        search = new SearchFixtures(jdbc);
        messages = new SearchMessageFixtures(jdbc);
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
        conversationId = search.saveConversation(
                workspaceId,
                memberId,
                "주간 회의를 회의록에 만들어 줘",
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
        notionFixtures.connect(
                workspaceId,
                memberId,
                SearchNotionFixtures.NOTION_WORKSPACE
        );
        when(
                notion.createPage(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchNotionCreatedPage(
                        "created-page-id",
                        CREATED_URL
                )
        );
        when(
                notion.fetchDatabase(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenReturn(SearchNotionFixtures.DATABASE);
    }

    @Test
    @DisplayName("일반 페이지 아래 카드를 실행하면 연결로 한 번 만들고 DONE과 주소, 만든 페이지 이력을 저장한다")
    void execute_success_page() throws Exception {
        // given
        SearchWriteCard card = pageCard();

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(card.getId()))
                .andExpect(jsonPath("$.type").value("NOTION_PAGE_CREATE"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.location.name").value("회의록"))
                .andExpect(jsonPath("$.location.path").value("팀 위키 / 회의록"))
                .andExpect(jsonPath("$.location.kind").value("PAGE"))
                .andExpect(jsonPath("$.title").value(SearchNotionFixtures.TITLE))
                .andExpect(jsonPath("$.body").value(SearchNotionFixtures.BODY))
                .andExpect(jsonPath("$.databaseFields").value(nullValue()))
                .andExpect(jsonPath("$.sourceDocument.documentId").value(documentId))
                .andExpect(jsonPath("$.sourceDocument.title").value("주간 회의"))
                .andExpect(jsonPath("$.executable").value(true))
                .andExpect(jsonPath("$.createdPageUrl").value(CREATED_URL))
                .andExpect(jsonPath("$.retryReason").value(nullValue()))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.existingPages").isEmpty())
                .andExpect(jsonPath("$.locationNotionId").doesNotExist())
                .andExpect(jsonPath("$.notionWorkspaceId").doesNotExist());
        ArgumentCaptor<SearchNotionPageCreation> creation = ArgumentCaptor.forClass(SearchNotionPageCreation.class);
        verify(notion).createPage(
                eq(workspaceId),
                creation.capture(),
                eq(Duration.ofSeconds(10))
        );
        assertThat(creation.getValue()).isEqualTo(
                new SearchNotionPageCreation(
                        SearchWriteCardLocationKind.PAGE,
                        "page-location-id",
                        SearchNotionFixtures.TITLE,
                        List.of(),
                        SearchNotionFixtures.BODY
                )
        );
        verify(
                notion,
                never()
        ).fetchDatabase(
                anyLong(),
                any(),
                any()
        );
        assertThat(notionFixtures.createdPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 아래 카드는 지금 DB 구조가 같을 때 제목과 회의 날짜 칸으로 만든다")
    void execute_success_database() throws Exception {
        // given
        SearchWriteCard card = databaseCard(true);

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.location.kind").value("DATABASE"))
                .andExpect(jsonPath("$.databaseFields.length()").value(2))
                .andExpect(jsonPath("$.databaseFields[0].name").value("이름"))
                .andExpect(jsonPath("$.databaseFields[0].value").value(SearchNotionFixtures.TITLE))
                .andExpect(jsonPath("$.databaseFields[1].name").value("날짜"))
                .andExpect(jsonPath("$.databaseFields[1].value").value("2026-11-13"))
                .andExpect(jsonPath("$.databaseFields[0].propertyType").doesNotExist());
        verify(notion).fetchDatabase(
                workspaceId,
                "data-source-id",
                Duration.ofSeconds(8)
        );
        verify(notion).createPage(
                workspaceId,
                new SearchNotionPageCreation(
                        SearchWriteCardLocationKind.DATABASE,
                        "data-source-id",
                        SearchNotionFixtures.TITLE,
                        SearchNotionFixtures.databaseFields(),
                        SearchNotionFixtures.BODY
                ),
                Duration.ofSeconds(10)
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"NONE,PENDING,retryReason,NOTION_CONNECTION_REQUIRED",
            "OTHER,FAILED,failureReason,NOTION_CONNECTION_CHANGED",
            "EXPIRED,PENDING,retryReason,NOTION_RECONNECT_REQUIRED",
            "OTHER_EXPIRED,FAILED,failureReason,NOTION_CONNECTION_CHANGED"})
    @DisplayName("연결 없음, 다른 노션, 만료는 노션을 부르지 않고 카드 상태로 알린다")
    void execute_connectionStates(
            String connection,
            String cardStatus,
            String reasonField,
            String reason
    ) throws Exception {
        // given
        SearchWriteCard card = pageCard();
        changeConnection(connection);

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(cardStatus))
                .andExpect(jsonPath("$." + reasonField).value(reason))
                .andExpect(jsonPath("$.createdPageUrl").value(nullValue()));
        verifyNoInteractions(notion);
        assertThat(notionFixtures.createdPages()).isZero();
    }

    @Test
    @DisplayName("연결 없음으로 대기가 된 카드는 같은 노션을 새로 연결하면 같은 카드로 실행된다")
    void execute_success_afterReconnectingSameNotion() throws Exception {
        // given
        SearchWriteCard card = pageCard();
        changeConnection("NONE");
        execute(card.getId()).andExpect(jsonPath("$.retryReason").value("NOTION_CONNECTION_REQUIRED"));
        long other = documents.saveMember("다시 연결한 멤버");
        documents.join(
                workspaceId,
                other
        );
        notionFixtures.connect(
                workspaceId,
                other,
                SearchNotionFixtures.NOTION_WORKSPACE
        );

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.retryReason").value(nullValue()));
    }

    @Test
    @DisplayName("DB 구조가 카드의 칸과 달라졌으면 만들지 않고 칸을 갱신해 대기로 되돌린다")
    void execute_pending_databaseFieldsChanged() throws Exception {
        // given
        SearchWriteCard card = databaseCard(true);
        when(
                notion.fetchDatabase(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchNotionDatabase(
                        "data-source-id",
                        "회의록",
                        "팀 위키 / 회의록",
                        List.of(
                                new SearchWriteCardDatabaseFields.Property(
                                        "제목",
                                        "title"
                                ),
                                new SearchWriteCardDatabaseFields.Property(
                                        "시작일",
                                        "date"
                                ),
                                new SearchWriteCardDatabaseFields.Property(
                                        "마감일",
                                        "date"
                                )
                        )
                )
        );

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryReason").value("DATABASE_FIELDS_CHANGED"))
                .andExpect(jsonPath("$.databaseFields.length()").value(1))
                .andExpect(jsonPath("$.databaseFields[0].name").value("제목"))
                .andExpect(jsonPath("$.databaseFields[0].value").value(SearchNotionFixtures.TITLE));
        verify(
                notion,
                never()
        ).createPage(
                anyLong(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("요청이 많다는 거절의 대기 시간이 한도 이하면 그만큼 기다려 한 번 더 요청해 만든다")
    void execute_success_rateLimitedShortWait() throws Exception {
        // given
        SearchWriteCard card = pageCard();
        when(
                notion.createPage(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenThrow(rateLimited(Duration.ofMillis(10)))
                .thenReturn(
                        new SearchNotionCreatedPage(
                                "created-page-id",
                                CREATED_URL
                        )
                );

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        verify(
                notion,
                times(2)
        ).createPage(
                anyLong(),
                any(),
                any()
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {"긴 대기,PT11S,1", "대기 시간 모름,NULL,1", "재요청도 거절,PT0.01S,2"}, nullValues = "NULL")
    @DisplayName("요청이 많다는 거절이 길거나 대기 시간을 모르거나 재요청도 거절되면 대기로 되돌린다")
    void execute_pending_rateLimited(
            String scenario,
            Duration retryAfter,
            int calls
    ) throws Exception {
        // given
        SearchWriteCard card = pageCard();
        SearchNotionException rejected = rateLimited(retryAfter);
        when(
                notion.createPage(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenThrow(
                rejected,
                rejected
        );

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryReason").value("NOTION_RATE_LIMITED"));
        verify(
                notion,
                times(calls)
        ).createPage(
                anyLong(),
                any(),
                any()
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"PERMISSION_DENIED,FAILED,failureReason,LOCATION_PERMISSION_LOST",
            "NOT_FOUND,FAILED,failureReason,LOCATION_PERMISSION_LOST",
            "CONTENT_TOO_LONG,FAILED,failureReason,BODY_TOO_LONG", "TIMEOUT,FAILED,failureReason,NOTION_NO_RESPONSE",
            "FAILED,FAILED,failureReason,NOTION_NO_RESPONSE",
            "CONNECTION_EXPIRED,PENDING,retryReason,NOTION_RECONNECT_REQUIRED",
            "NOT_CONNECTED,PENDING,retryReason,NOTION_CONNECTION_REQUIRED"})
    @DisplayName("노션 실패는 200 응답의 카드 상태로 알리고 만든 페이지 이력을 저장하지 않는다")
    void execute_notionFailures(
            SearchNotionFailure failure,
            String cardStatus,
            String reasonField,
            String reason
    ) throws Exception {
        // given
        SearchWriteCard card = pageCard();
        when(
                notion.createPage(
                        anyLong(),
                        any(),
                        any()
                )
        ).thenThrow(new SearchNotionException(failure));

        // when & then
        execute(card.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(cardStatus))
                .andExpect(jsonPath("$." + reasonField).value(reason));
        verify(notion).createPage(
                anyLong(),
                any(),
                any()
        );
        assertThat(notionFixtures.createdPages()).isZero();
    }

    @Test
    @DisplayName("이미 DONE인 카드를 다시 누르면 노션에 다시 쓰지 않고 같은 결과를 준다")
    void execute_success_alreadyDone() throws Exception {
        // given
        SearchWriteCard card = pageCard();
        String first = execute(card.getId()).andReturn()
                .getResponse()
                .getContentAsString();

        // when
        String second = execute(card.getId()).andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // then
        assertThat(second).isEqualTo(first);
        verify(notion).createPage(
                anyLong(),
                any(),
                any()
        );
        assertThat(notionFixtures.createdPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버는 남의 카드를 실행하거나 취소할 수 없다")
    void writeCard_failure_otherMember() throws Exception {
        // given
        SearchWriteCard card = pageCard();
        long other = documents.saveMember("다른 멤버");
        documents.join(
                workspaceId,
                other
        );

        // when & then
        for (String action : List.of(
                "execute",
                "cancel"
        )) {
            mvc.perform(
                    request(
                            action,
                            workspaceId,
                            conversationId,
                            card.getId(),
                            other
                    )
            )
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SEARCH_ACCESS_DENIED"));
        }
        verifyNoInteractions(notion);
    }

    @Test
    @DisplayName("Workspace를 나간 질문자는 자기 카드도 실행할 수 없다")
    void execute_failure_leftMember() throws Exception {
        // given
        SearchWriteCard card = pageCard();
        documents.leave(
                workspaceId,
                memberId
        );

        // when & then
        execute(card.getId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SEARCH_ACCESS_DENIED"));
        verifyNoInteractions(notion);
    }

    @Test
    @DisplayName("없는 카드와 다른 대화의 카드는 404다")
    void writeCard_failure_notFound() throws Exception {
        // given
        long otherConversation = search.saveConversation(
                workspaceId,
                memberId,
                "다른 질문",
                "다른 답변"
        );
        SearchWriteCard otherCard = notionFixtures.pageCard(
                new SearchWriteCardOrigin(
                        messages.messageId(
                                otherConversation,
                                2
                        ),
                        otherConversation,
                        workspaceId,
                        memberId
                ),
                documentId
        );

        // when & then
        for (long cardId : List.of(
                Long.MAX_VALUE,
                otherCard.getId()
        )) {
            execute(cardId).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("WRITE_CARD_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("DB 속성을 확인하지 못한 카드는 실행 요청을 409로 거절한다")
    void execute_failure_notExecutable() throws Exception {
        // given
        SearchWriteCard card = databaseCard(false);

        // when & then
        execute(card.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRITE_CARD_NOT_EXECUTABLE"));
        verifyNoInteractions(notion);
    }

    @Test
    @DisplayName("대기 카드 취소는 CANCELED, 다시 취소해도 200, 실행된 카드 취소는 409다")
    void cancel_states() throws Exception {
        // given
        SearchWriteCard pending = pageCard();
        long secondAnswer = addAnswer(3);
        SearchWriteCard done = notionFixtures.pageCard(
                origin(secondAnswer),
                documentId
        );
        execute(done.getId()).andExpect(jsonPath("$.status").value("DONE"));

        // when & then
        cancel(pending.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        cancel(pending.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        cancel(done.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRITE_CARD_NOT_PENDING"));
        execute(pending.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("메시지 조회는 답변마다 노션 근거, 안내 사유, 카드와 이미 만든 페이지를 함께 준다")
    void findMessages_notionFields() throws Exception {
        // given
        SearchWriteCard first = pageCard();
        execute(first.getId()).andExpect(jsonPath("$.status").value("DONE"));
        long secondAnswer = addAnswer(3);
        notionFixtures.pageCard(
                origin(secondAnswer),
                documentId
        );
        notionFixtures.saveEvidence(
                secondAnswer,
                1,
                "출시 계획",
                "https://www.notion.so/plan"
        );
        notionFixtures.saveNotice(
                secondAnswer,
                SearchNotionNoticeReason.READ_FAILED
        );
        changeConnection("NONE");

        // when & then
        mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/messages",
                        workspaceId,
                        conversationId
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[0].notionEvidences").isEmpty())
                .andExpect(jsonPath("$.items[0].notionNotice").value(nullValue()))
                .andExpect(jsonPath("$.items[0].writeCard").value(nullValue()))
                .andExpect(jsonPath("$.items[1].notionEvidences").isEmpty())
                .andExpect(jsonPath("$.items[1].writeCard.status").value("DONE"))
                .andExpect(jsonPath("$.items[1].writeCard.createdPageUrl").value(CREATED_URL))
                .andExpect(jsonPath("$.items[1].writeCard.existingPages").isEmpty())
                .andExpect(jsonPath("$.items[3].notionEvidences[0].title").value("출시 계획"))
                .andExpect(jsonPath("$.items[3].notionEvidences[0].url").value("https://www.notion.so/plan"))
                .andExpect(jsonPath("$.items[3].notionNotice").value("READ_FAILED"))
                .andExpect(jsonPath("$.items[3].writeCard.status").value("PENDING"))
                .andExpect(jsonPath("$.items[3].writeCard.existingPages.length()").value(1))
                .andExpect(jsonPath("$.items[3].writeCard.existingPages[0].url").value(CREATED_URL))
                .andExpect(jsonPath("$.items[3].writeCard.existingPages[0].createdAt").exists());
    }

    @Test
    @DisplayName("끝난 답변의 재연결 SSE는 근거 다음, 끝 이벤트 바로 앞에 노션 근거, 안내 사유, 카드를 보낸다")
    void reconnect_notionEvents() throws Exception {
        // given
        SearchQuestionAcceptedResult accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "회의록에 만들어 줘",
                        UUID.randomUUID()
                )
        );
        long answer = accepted.answerMessageId();
        jdbc.sql("UPDATE search_messages SET content = '확인해 주세요', status = 'COMPLETED' WHERE id = :id")
                .param(
                        "id",
                        answer
                )
                .update();
        SearchWriteCard card = notionFixtures.pageCard(
                new SearchWriteCardOrigin(
                        answer,
                        accepted.conversationId(),
                        workspaceId,
                        memberId
                ),
                documentId
        );
        notionFixtures.saveEvidence(
                answer,
                1,
                "출시 계획",
                "https://www.notion.so/plan"
        );
        notionFixtures.saveNotice(
                answer,
                SearchNotionNoticeReason.CONNECTION_EXPIRED
        );

        // when
        MvcResult result = mvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/events",
                        workspaceId,
                        accepted.conversationId(),
                        answer
                ).cookie(cookie(memberId))
                        .accept(MediaType.TEXT_EVENT_STREAM)
        )
                .andExpect(
                        MockMvcResultMatchers.request()
                                .asyncStarted()
                )
                .andReturn();
        result.getAsyncResult(5000);
        String body = result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // then
        List<String> events = List.of(body.split("\n\n"));
        assertThat(events).extracting(
                event -> event.substring(
                        "event:".length(),
                        event.indexOf('\n')
                )
        )
                .containsExactly(
                        "snapshot",
                        "notion_evidence",
                        "notion_notice",
                        "write_card",
                        "completed"
                );
        assertThat(events.get(1)).contains(
                "\"answerId\":" + answer,
                "\"title\":\"출시 계획\"",
                "\"url\":\"https://www.notion.so/plan\""
        );
        assertThat(events.get(2)).contains(
                "\"answerId\":" + answer,
                "\"reason\":\"CONNECTION_EXPIRED\""
        );
        assertThat(events.get(3)).contains(
                "\"answerId\":" + answer,
                "\"card\":{\"id\":" + card.getId(),
                "\"status\":\"PENDING\"",
                "\"existingPages\":[]"
        );
        assertThat(body).doesNotContain(
                "answerMessageId",
                "page-location-id",
                SearchNotionFixtures.NOTION_WORKSPACE
        );
    }

    @Test
    @DisplayName("로그인하지 않았거나 CSRF가 없으면 실행하지 않는다")
    void execute_failure_unauthenticatedOrCsrf() throws Exception {
        // given
        SearchWriteCard card = pageCard();

        // when & then
        mvc.perform(
                withCsrf(
                        post(
                                BASE + "/execute",
                                workspaceId,
                                conversationId,
                                card.getId()
                        )
                )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(
                post(
                        BASE + "/execute",
                        workspaceId,
                        conversationId,
                        card.getId()
                ).cookie(cookie(memberId))
        )
                .andExpect(status().isForbidden());
        verifyNoInteractions(notion);
    }

    @Test
    @DisplayName("OpenAPI에 실행과 취소의 CSRF 헤더, 오류 응답, 카드 schema를 문서화한다")
    void writeCard_openApi() throws Exception {
        // when
        ResultActions docs = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());

        // then
        for (String action : List.of(
                "execute",
                "cancel"
        )) {
            String operation = "$.paths['/api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}/"
                    + action + "'].post";
            docs.andExpect(
                    jsonPath(operation + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true))
            )
                    .andExpect(jsonPath(operation + ".responses['200']").exists())
                    .andExpect(jsonPath(operation + ".responses['401']").exists())
                    .andExpect(jsonPath(operation + ".responses['403']").exists())
                    .andExpect(jsonPath(operation + ".responses['404']").exists())
                    .andExpect(jsonPath(operation + ".responses['409']").exists());
        }
        docs.andExpect(
                jsonPath("$.components.schemas.SearchWriteCardResponse.required").value(
                        hasItems(
                                "id",
                                "type",
                                "status",
                                "location",
                                "title",
                                "body",
                                "databaseFields",
                                "sourceDocument",
                                "executable",
                                "createdPageUrl",
                                "retryReason",
                                "failureReason",
                                "existingPages"
                        )
                )
        )
                .andExpect(
                        jsonPath("$.components.schemas.SearchMessageListItemResponse.required").value(
                                hasItems(
                                        "notionEvidences",
                                        "notionNotice",
                                        "writeCard"
                                )
                        )
                );
    }

    private SearchWriteCard pageCard() {
        return notionFixtures.pageCard(
                origin(answerId),
                documentId
        );
    }

    private SearchWriteCard databaseCard(boolean executable) {
        return notionFixtures.databaseCard(
                origin(answerId),
                documentId,
                executable
        );
    }

    private SearchWriteCardOrigin origin(long answer) {
        return new SearchWriteCardOrigin(
                answer,
                conversationId,
                workspaceId,
                memberId
        );
    }

    private long addAnswer(int questionSequence) {
        search.saveMessage(
                conversationId,
                "USER",
                questionSequence,
                "다시 만들어 줘",
                "RECEIVED"
        );
        search.saveMessage(
                conversationId,
                "ASSISTANT",
                questionSequence + 1,
                "다시 확인해 주세요",
                "COMPLETED"
        );
        return messages.messageId(
                conversationId,
                questionSequence + 1
        );
    }

    private void changeConnection(String connection) {
        if (connection.startsWith("NONE") || connection.startsWith("OTHER")) {
            jdbc.sql("DELETE FROM search_notion_connections")
                    .update();
        }
        if (connection.startsWith("OTHER")) {
            notionFixtures.connect(
                    workspaceId,
                    memberId,
                    "other-notion-workspace"
            );
        }
        if (connection.endsWith("EXPIRED")) {
            notionFixtures.expire(workspaceId);
        }
    }

    private SearchNotionException rateLimited(Duration retryAfter) {
        return new SearchNotionException(
                SearchNotionFailure.RATE_LIMITED,
                retryAfter,
                null
        );
    }

    private ResultActions execute(long cardId) throws Exception {
        return mvc.perform(
                request(
                        "execute",
                        workspaceId,
                        conversationId,
                        cardId,
                        memberId
                )
        );
    }

    private ResultActions cancel(long cardId) throws Exception {
        return mvc.perform(
                request(
                        "cancel",
                        workspaceId,
                        conversationId,
                        cardId,
                        memberId
                )
        );
    }

    private MockHttpServletRequestBuilder request(
            String action,
            long workspace,
            long conversation,
            long cardId,
            long member
    ) throws Exception {
        return withCsrf(
                post(
                        BASE + "/" + action,
                        workspace,
                        conversation,
                        cardId
                ).cookie(cookie(member))
        );
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder) throws Exception {
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        return builder.cookie(csrf)
                .header(
                        "X-XSRF-TOKEN",
                        csrf.getValue()
                );
    }

    private Cookie cookie(long member) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                member,
                                "멤버",
                                null
                        )
                )
        );
    }
}
