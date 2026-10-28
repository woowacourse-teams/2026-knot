package com.knot.backend.search.application;

import com.knot.backend.search.infrastructure.llm.SearchQwenIntentClassifier;
import com.knot.backend.search.infrastructure.llm.SearchTransportCancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchDocumentPage;
import com.knot.backend.search.application.dto.result.SearchDocumentCard;
import com.knot.backend.search.application.dto.result.SearchDocumentSlice;
import com.knot.backend.search.infrastructure.persistence.SearchDocumentQueryAdapter;
import com.knot.backend.search.infrastructure.llm.SearchAnswerPrompt;
import com.knot.backend.search.infrastructure.mcp.SearchToolTokenCodec;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knot.search.agent.enabled=true", "knot.search.agent.signing-key=test-signing-key-32-characters-minimum",
        "knot.search.agent.retrieval-mode=AGENT"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchDocumentToolServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchToolAccess access;
    @Autowired
    private SearchDocumentQueryAdapter query;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private SearchMessageFixtures messages;
    private SearchDocumentToolService service;
    private SearchToolScope scope;
    private SearchAgentProperties properties;
    private long workspaceId;
    private long memberId;
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;
    @Autowired
    private com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery history;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        messages = new SearchMessageFixtures(jdbc);
        memberId = fixtures.saveMember("탐색자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        long answerId = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "회의 결정",
                        UUID.randomUUID()
                )
        )
                .answerMessageId();
        SearchAnswerWorkResult work = executions.claim(answerId)
                .orElseThrow();
        scope = SearchToolScope.from(
                work,
                NOW,
                NOW.plusSeconds(120)
        );
        properties = new SearchAgentProperties();
        properties.setSigningKey("test-signing-key-32-characters-minimum");
        service = new SearchDocumentToolService(
                query,
                access,
                new SearchToolTokenCodec(
                        mapper,
                        properties,
                        clock
                ),
                properties
        );
    }

    @Test
    @DisplayName("현재 Workspace의 생성 완료 DRAFT와 ARCHIVED를 페이지로 열거한다")
    void list_success_generatedScopedPages() {
        long first = saveDocument(
                workspaceId,
                "ARCHIVED",
                "첫 회의",
                "내용"
        );
        long second = saveDocument(
                workspaceId,
                "DRAFT",
                "두 번째 회의",
                "내용"
        );
        saveDocument(
                fixtures.saveWorkspace(),
                "ARCHIVED",
                "다른 공간",
                "내용"
        );
        SearchDocumentPage page = service.list(
                scope,
                parameters(
                        null,
                        null,
                        1
                )
        );
        assertThat(page.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(second);
        assertThat(
                page.items()
                        .getFirst()
                        .archivedAt()
        ).isNull();
        assertThat(page.nextCursor()).isNotNull();
        SearchDocumentPage previous = service.list(
                scope,
                parameters(
                        null,
                        page.nextCursor(),
                        1
                )
        );
        assertThat(previous.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(first);
        assertThat(previous.nextCursor()).isNull();
    }

    @Test
    @DisplayName("목록은 기간과 주제 조건을 적용하고 조건이 바뀐 커서를 거절한다")
    void list_failure_cursorFilterChanged() {
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "첫",
                "내용"
        );
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "둘",
                "내용"
        );
        SearchDocumentPage page = service.list(
                scope,
                parameters(
                        null,
                        null,
                        1
                )
        );
        SearchDocumentParameters changed = new SearchDocumentParameters(
                null,
                "다른 주제",
                null,
                null,
                page.nextCursor(),
                1
        );
        assertThatThrownBy(
                () -> service.list(
                        scope,
                        changed
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
        SearchDocumentParameters outside = new SearchDocumentParameters(
                null,
                null,
                NOW,
                NOW.plusSeconds(1),
                null,
                20
        );
        assertThat(
                service.list(
                        scope,
                        outside
                )
                        .items()
        ).isEmpty();
    }

    @Test
    @DisplayName("날짜 검색은 실제 PostgreSQL에서 서울 기준 시작은 포함하고 다음 날은 제외한다")
    void retrieve_success_dateBoundsInPostgres() {
        // given
        long inside = saveDocument(
                workspaceId,
                "ARCHIVED",
                "어제 회의",
                "내용"
        );
        long outside = saveDocument(
                workspaceId,
                "ARCHIVED",
                "오늘 회의",
                "내용"
        );
        jdbc.sql(
                "UPDATE recording_sessions SET started_at = :time, ended_at = :time, last_seen_at = :time WHERE id = (SELECT recording_session_id FROM documents WHERE id = :id)"
        )
                .param(
                        "time",
                        java.sql.Timestamp.from(Instant.parse("2026-10-09T15:00:00Z"))
                )
                .param(
                        "id",
                        inside
                )
                .update();
        jdbc.sql(
                "UPDATE recording_sessions SET started_at = :time, ended_at = :time, last_seen_at = :time WHERE id = (SELECT recording_session_id FROM documents WHERE id = :id)"
        )
                .param(
                        "time",
                        java.sql.Timestamp.from(Instant.parse("2026-10-10T15:00:00Z"))
                )
                .param(
                        "id",
                        outside
                )
                .update();
        com.knot.backend.search.infrastructure.llm.SearchFastPathDocumentRetriever retriever = new com.knot.backend.search.infrastructure.llm.SearchFastPathDocumentRetriever(
                query,
                access,
                new com.knot.backend.search.infrastructure.llm.SearchQueryNormalizer()
        );
        // when
        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "어제 회의 요약"
        );
        // then
        assertThat(result.documents()).extracting(SearchDocumentResult::documentId)
                .containsExactly(inside);
        assertThat(
                result.documents()
                        .getFirst()
                        .recordingStartedAt()
        ).isEqualTo(Instant.parse("2026-10-09T15:00:00Z"));
        assertThat(
                result.documents()
                        .getFirst()
                        .createdAt()
        ).isNotNull();
    }

    @Test
    @DisplayName("멤버가 탈퇴하면 현재 작업의 문서 목록도 읽을 수 없다")
    void list_failure_membershipRemoved() {
        jdbc.sql(
                "UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP WHERE workspace_id = :workspace AND member_id = :member"
        )
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        memberId
                )
                .update();
        assertThatThrownBy(
                () -> service.list(
                        scope,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("잘못된 크기와 시각 범위를 접수 전에 거절한다")
    void list_failure_invalidParameters() {
        assertThatThrownBy(
                () -> parameters(
                        null,
                        null,
                        0
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
        assertThatThrownBy(
                () -> new SearchDocumentParameters(
                        null,
                        null,
                        NOW,
                        NOW.minusSeconds(1),
                        null,
                        20
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    @DisplayName("한글 검색은 제목의 관련도가 높은 문서를 먼저 반환한다")
    void search_success_titleRankedFirst() {
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "회고",
                "결제 장애 원인"
        );
        long title = saveDocument(
                workspaceId,
                "DRAFT",
                "결제 장애",
                "복구 완료"
        );
        saveDocument(
                workspaceId,
                "DRAFT",
                "다른 안건",
                "미보관"
        );
        saveDocument(
                fixtures.saveWorkspace(),
                "ARCHIVED",
                "결제 장애",
                "외부"
        );
        assertThat(
                service.search(
                        scope,
                        parameters(
                                "결제",
                                null,
                                1
                        )
                )
                        .items()
        ).extracting(SearchDocumentCard::documentId)
                .containsExactly(title);
    }

    @Test
    @DisplayName("검색 커서는 다음 후보를 이어서 반환한다")
    void search_success_nextPage() {
        long body = saveDocument(
                workspaceId,
                "ARCHIVED",
                "회고",
                "결제 원인"
        );
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "결제 장애",
                "복구"
        );
        SearchDocumentPage first = service.search(
                scope,
                parameters(
                        "결제",
                        null,
                        1
                )
        );
        SearchDocumentPage next = service.search(
                scope,
                parameters(
                        "결제",
                        first.nextCursor(),
                        1
                )
        );
        assertThat(next.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(body);
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void search_failure_changedQueryCursor() {
        String cursor = searchCursor();
        assertThatThrownBy(
                () -> service.search(
                        scope,
                        parameters(
                                "변경",
                                cursor,
                                1
                        )
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    void search_success_literalWildcard() {
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "결제",
                "원인"
        );
        assertThat(
                service.search(
                        scope,
                        parameters(
                                "%",
                                null,
                                20
                        )
                )
                        .items()
        ).isEmpty();
    }

    @Test
    void search_failure_missingQuery() {
        assertThatThrownBy(
                () -> service.search(
                        scope,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    void read_success_unicodeContinuation() {
        properties.setReadCodePoints(3);
        long document = saveDocument(
                workspaceId,
                "ARCHIVED",
                "결정",
                "가🙂나다😃라"
        );
        SearchDocumentSlice first = service.read(
                scope,
                document,
                null
        );
        assertThat(first.content()).isEqualTo("가🙂나");
        assertThat(first.truncated()).isTrue();
        SearchDocumentSlice next = service.read(
                scope,
                document,
                first.nextCursor()
        );
        assertThat(next.content()).isEqualTo("다😃라");
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void read_failure_changedRevision() {
        properties.setReadCodePoints(3);
        long document = saveDocument(
                workspaceId,
                "ARCHIVED",
                "결정",
                "가🙂나다😃라"
        );
        String cursor = service.read(
                scope,
                document,
                null
        )
                .nextCursor();
        jdbc.sql("UPDATE documents SET content = '변경됨' WHERE id = :id")
                .param(
                        "id",
                        document
                )
                .update();
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        document,
                        cursor
                )
        ).hasMessage("DOCUMENT_CHANGED");
    }

    @Test
    void read_failure_cursorForDifferentDocument() {
        properties.setReadCodePoints(3);
        long first = saveDocument(
                workspaceId,
                "ARCHIVED",
                "첫",
                "긴본문입니다"
        );
        long second = saveDocument(
                workspaceId,
                "ARCHIVED",
                "둘",
                "긴본문입니다"
        );
        String cursor = service.read(
                scope,
                first,
                null
        )
                .nextCursor();
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        second,
                        cursor
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    void read_failure_searchCursor() {
        String cursor = searchCursor();
        long document = saveDocument(
                workspaceId,
                "ARCHIVED",
                "결정",
                "본문"
        );
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        document,
                        cursor
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    void read_failure_foreignWorkspace() {
        long document = saveDocument(
                fixtures.saveWorkspace(),
                "ARCHIVED",
                "외부",
                "비공개"
        );
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        document,
                        null
                )
        ).hasMessage("DOCUMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("미확인 DRAFT도 본문을 읽고 보관 시각은 null로 반환한다")
    void read_success_draft() {
        long document = saveDocument(
                workspaceId,
                "DRAFT",
                "초안",
                "비공개"
        );
        SearchDocumentSlice result = service.read(
                scope,
                document,
                null
        );
        assertThat(result.content()).isEqualTo("비공개");
        assertThat(
                result.document()
                        .archivedAt()
        ).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ARCHIVED"})
    @DisplayName("생성 시각 경계까지 검색하고 이후 문서는 목록·검색·읽기에서 제외한다")
    void tools_success_creationCutoff(String status) {
        // given
        long before = saveDocument(
                workspaceId,
                status,
                "결정 이전",
                "본문"
        );
        long boundary = saveDocument(
                workspaceId,
                status,
                "결정 경계",
                "본문"
        );
        long after = saveDocument(
                workspaceId,
                status,
                "결정 이후",
                "본문"
        );
        setCreatedAt(
                before,
                NOW.minusSeconds(1)
        );
        setCreatedAt(
                boundary,
                NOW
        );
        setCreatedAt(
                after,
                NOW.plusSeconds(1)
        );
        // when
        SearchDocumentPage listed = service.list(
                scope,
                parameters(
                        null,
                        null,
                        20
                )
        );
        SearchDocumentPage searched = service.search(
                scope,
                parameters(
                        "결정",
                        null,
                        20
                )
        );
        // then
        assertThat(listed.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(
                        boundary,
                        before
                );
        assertThat(searched.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(
                        boundary,
                        before
                );
        assertThat(
                service.read(
                        scope,
                        boundary,
                        null
                )
                        .content()
        ).isEqualTo("본문");
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        after,
                        null
                )
        ).hasMessage("DOCUMENT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("페이지 사이 보관 전환에도 기존 커서로 다음 문서를 읽는다")
    void page_success_archiveTransition(boolean search) {
        // given
        long older = saveDocument(
                workspaceId,
                "DRAFT",
                "결정 이전",
                "본문"
        );
        long newer = saveDocument(
                workspaceId,
                "DRAFT",
                "결정 최근",
                "본문"
        );
        SearchDocumentPage first = documentPage(
                search,
                null
        );
        assertThat(first.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(newer);
        archiveDocument(older);
        archiveDocument(newer);
        // when
        SearchDocumentPage next = documentPage(
                search,
                first.nextCursor()
        );
        // then
        assertThat(next.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(older);
        assertThat(
                next.items()
                        .getFirst()
                        .status()
        ).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(next.nextCursor()).isNull();
        assertThat(
                service.read(
                        scope,
                        older,
                        null
                )
                        .content()
        ).isEqualTo("본문");
    }

    @Test
    @DisplayName("DRAFT 본문을 이어 읽는 동안 보관돼도 같은 revision으로 이어 읽는다")
    void read_success_archiveTransition() {
        // given
        properties.setReadCodePoints(3);
        long document = saveDocument(
                workspaceId,
                "DRAFT",
                "결정",
                "가🙂나다😃라"
        );
        SearchDocumentSlice first = service.read(
                scope,
                document,
                null
        );
        archiveDocument(document);
        // when
        SearchDocumentSlice next = service.read(
                scope,
                document,
                first.nextCursor()
        );
        // then
        assertThat(first.content() + next.content()).isEqualTo("가🙂나다😃라");
        assertThat(
                next.document()
                        .contentRevision()
        ).isEqualTo(
                first.document()
                        .contentRevision()
        );
        assertThat(
                next.document()
                        .status()
        ).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(
                next.document()
                        .archivedAt()
        ).isEqualTo(NOW.plusSeconds(1));
        assertThat(next.nextCursor()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CONFIRMED", "NOT_REQUIRED"})
    @DisplayName("다른 멤버의 DRAFT를 개인 확인 의무와 무관하게 탐색하고 확인 데이터를 변경하지 않는다")
    void tools_success_confirmationIndependent(String confirmation) {
        // given
        long creator = fixtures.saveMember("다른 작성자");
        fixtures.join(
                workspaceId,
                creator
        );
        long document = messages.saveDocument(
                workspaceId,
                creator
        );
        if (!"NOT_REQUIRED".equals(confirmation)) {
            jdbc.sql(
                    "INSERT INTO document_confirmations (document_id, member_id, confirmed_at) "
                            + "VALUES (:d, :m, CASE WHEN :confirmed THEN CAST(:time AS timestamptz) END)"
            )
                    .param(
                            "d",
                            document
                    )
                    .param(
                            "m",
                            memberId
                    )
                    .param(
                            "confirmed",
                            "CONFIRMED".equals(confirmation)
                    )
                    .param(
                            "time",
                            java.sql.Timestamp.from(NOW)
                    )
                    .update();
        }
        Map<String, Object> documentBefore = jdbc.sql("SELECT * FROM documents WHERE id = :d")
                .param(
                        "d",
                        document
                )
                .query()
                .singleRow();
        List<Map<String, Object>> confirmationsBefore = jdbc.sql("SELECT * FROM document_confirmations")
                .query()
                .listOfRows();
        // when
        SearchDocumentPage listed = service.list(
                scope,
                parameters(
                        null,
                        null,
                        20
                )
        );
        SearchDocumentPage searched = service.search(
                scope,
                parameters(
                        "문서",
                        null,
                        20
                )
        );
        SearchDocumentSlice read = service.read(
                scope,
                document,
                null
        );
        // then
        assertThat(listed.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(document);
        assertThat(searched.items()).extracting(SearchDocumentCard::documentId)
                .containsExactly(document);
        assertThat(
                read.document()
                        .status()
        ).isEqualTo(DocumentStatus.DRAFT);
        assertThat(
                jdbc.sql("SELECT * FROM documents WHERE id = :d")
                        .param(
                                "d",
                                document
                        )
                        .query()
                        .singleRow()
        ).isEqualTo(documentBefore);
        assertThat(
                jdbc.sql("SELECT * FROM document_confirmations")
                        .query()
                        .listOfRows()
        ).isEqualTo(confirmationsBefore);
    }

    @Test
    @DisplayName("실제 MCP의 세 문서 도구는 DRAFT 상태와 null 보관 시각을 전달한다")
    void mcp_success_draftMetadata() throws Exception {
        // given
        long document = saveDocument(
                workspaceId,
                "DRAFT",
                "결정",
                "내용"
        );
        String bearer = new SearchToolTokenCodec(
                mapper,
                properties,
                clock
        ).encode(
                "knot-search-mcp",
                scope
        );
        // when
        JsonNode definitions = mapper.readTree(
                rpc(
                        bearer,
                        "tools/list",
                        "{}"
                ).body()
        )
                .path("result");
        JsonNode listed = mcpResult(
                bearer,
                "list_documents",
                "{}"
        );
        JsonNode searched = mcpResult(
                bearer,
                "search_documents",
                "{\"query\":\"결정\"}"
        );
        JsonNode read = mcpResult(
                bearer,
                "read_document",
                "{\"documentId\":" + document + "}"
        );
        // then
        for (JsonNode tool : definitions.path("tools")) {
            assertThat(
                    tool.path("description")
                            .asString()
            ).contains(
                    "DRAFT",
                    "ARCHIVED"
            );
        }
        assertThat(
                listed.path("items")
                        .get(0)
                        .path("status")
                        .asString()
        ).isEqualTo("DRAFT");
        assertThat(
                searched.path("items")
                        .get(0)
                        .path("status")
                        .asString()
        ).isEqualTo("DRAFT");
        assertThat(
                read.path("document")
                        .path("status")
                        .asString()
        ).isEqualTo("DRAFT");
        assertThat(
                read.path("document")
                        .path("archivedAt")
                        .isNull()
        ).isTrue();
        assertThat(
                read.path("content")
                        .asString()
        ).isEqualTo("내용");
    }

    private JsonNode mcpResult(
            String bearer,
            String name,
            String arguments
    ) throws Exception {
        JsonNode result = mapper.readTree(
                rpc(
                        bearer,
                        "tools/call",
                        "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}"
                ).body()
        )
                .path("result");
        assertThat(
                result.path("isError")
                        .asBoolean()
        ).isFalse();
        return result.path("structuredContent");
    }

    private SearchDocumentPage documentPage(
            boolean search,
            String cursor
    ) {
        if (search) {
            return service.search(
                    scope,
                    parameters(
                            "결정",
                            cursor,
                            1
                    )
            );
        }
        return service.list(
                scope,
                parameters(
                        null,
                        cursor,
                        1
                )
        );
    }

    private void setCreatedAt(
            long document,
            Instant time
    ) {
        jdbc.sql(
                "UPDATE documents SET created_at = :time, "
                        + "archived_at = CASE WHEN status = 'ARCHIVED' THEN CAST(:time AS timestamptz) END WHERE id = :id"
        )
                .param(
                        "time",
                        java.sql.Timestamp.from(time)
                )
                .param(
                        "id",
                        document
                )
                .update();
    }

    private void archiveDocument(long document) {
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = :time WHERE id = :id")
                .param(
                        "time",
                        java.sql.Timestamp.from(NOW.plusSeconds(1))
                )
                .param(
                        "id",
                        document
                )
                .update();
    }

    @Test
    void read_failure_invalidId() {
        assertThatThrownBy(
                () -> service.read(
                        scope,
                        0,
                        null
                )
        ).hasMessage("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    @DisplayName("실제 MCP HTTP는 세 도구를 공개하고 답변별 인증 없이 호출할 수 없다")
    void mcp_success_scopedHttpTools() throws Exception {
        SearchToolTokenCodec codec = new SearchToolTokenCodec(
                mapper,
                properties,
                clock
        );
        String bearer = codec.encode(
                "knot-search-mcp",
                scope
        );
        String init = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}";
        assertThat(
                rpc(
                        bearer,
                        "initialize",
                        init
                ).statusCode()
        ).isEqualTo(200);
        java.net.http.HttpResponse<String> listed = rpc(
                bearer,
                "tools/list",
                "{}"
        );
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(
                mapper.readTree(listed.body())
                        .path("result")
                        .path("tools")
        ).hasSize(3);
        long doc = saveDocument(
                workspaceId,
                "ARCHIVED",
                "결정",
                "MCP 본문"
        );
        java.net.http.HttpResponse<String> read = rpc(
                bearer,
                "tools/call",
                "{\"name\":\"read_document\",\"arguments\":{\"documentId\":" + doc + "}}"
        );
        assertThat(read.body()).contains("MCP 본문");
        assertThat(
                rpc(
                        "invalid",
                        "tools/list",
                        "{}"
                ).statusCode()
        ).isEqualTo(401);
        jdbc.sql("DELETE FROM workspace_members WHERE workspace_id = :w AND member_id = :m")
                .param(
                        "w",
                        workspaceId
                )
                .param(
                        "m",
                        memberId
                )
                .update();
        assertThat(
                rpc(
                        bearer,
                        "tools/list",
                        "{}"
                ).statusCode()
        ).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 MCP HTTP와 LLM HTTP 어댑터를 worker로 연결해 답변과 근거를 완료 저장한다")
    void execute_success_actualMcpAndLlmHttp(boolean followup) throws Exception {
        long document = saveDocument(
                workspaceId,
                "DRAFT",
                "결제 결정",
                "결제 장애는 김개발이 복구하기로 결정했다."
        );
        properties.setMcpBaseUrl(java.net.URI.create("http://127.0.0.1:" + port));
        try (com.knot.backend.testsupport.LlmHttpServer server = new com.knot.backend.testsupport.LlmHttpServer();
                SearchTransportCancellation cancellation = new SearchTransportCancellation(
                        access,
                        java.time.Duration.ofMillis(250)
                );
                com.knot.backend.search.infrastructure.llm.SearchAgentHttp transport = new com.knot.backend.search.infrastructure.llm.SearchAgentHttp(
                        java.net.http.HttpClient.newHttpClient(),
                        mapper,
                        clock,
                        262144,
                        cancellation
                )) {
            java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
            List<JsonNode> captured = new ArrayList<>();
            server.respond(exchange -> {
                captured.add(mapper.readTree(server.requestBody()));
                String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"intent\\\":\\\"MEETING_SEARCH\\\"}\"}}]}";
                if (!mapper.readTree(server.requestBody())
                        .has("response_format")) {
                    body = response(
                            turns.incrementAndGet(),
                            document
                    );
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(
                        200,
                        bytes.length
                );
                exchange.getResponseBody()
                        .write(bytes);
            });
            com.knot.backend.global.config.LlmProperties llmProperties = new com.knot.backend.global.config.LlmProperties();
            llmProperties.setBaseUrl(server.baseUrl());
            llmProperties.setApiToken("test-token");
            llmProperties.setModel("test-model");
            com.knot.backend.search.infrastructure.llm.SearchLlmClient llm = new com.knot.backend.search.infrastructure.llm.SearchLlmClient(
                    transport,
                    llmProperties
            );
            com.knot.backend.search.infrastructure.mcp.SearchMcpClient mcp = new com.knot.backend.search.infrastructure.mcp.SearchMcpClient(
                    transport,
                    new SearchToolTokenCodec(
                            mapper,
                            properties,
                            clock
                    ),
                    properties,
                    mapper
            );
            org.springframework.beans.factory.support.StaticListableBeanFactory adapters = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            adapters.addBean(
                    "retriever",
                    new com.knot.backend.search.infrastructure.llm.SearchMcpDocumentRetriever(
                            llm,
                            mcp,
                            history,
                            access,
                            properties,
                            mapper
                    )
            );
            adapters.addBean(
                    "generator",
                    new com.knot.backend.search.infrastructure.llm.SearchStreamingAnswerGenerator(
                            llm,
                            history,
                            access,
                            properties,
                            mapper
                    )
            );
            adapters.addBean(
                    "classifier",
                    new SearchQwenIntentClassifier(
                            llm,
                            history,
                            access,
                            properties,
                            mapper
                    )
            );
            SearchAnswerWorker worker = new SearchAnswerWorker(
                    executions,
                    adapters.getBeanProvider(SearchDocumentRetriever.class),
                    adapters.getBeanProvider(SearchAnswerGenerator.class),
                    clock,
                    properties,
                    adapters.getBeanProvider(SearchIntentClassifier.class)
            );
            SearchQuestionAcceptedResult accepted = intake.acceptFirst(
                    workspaceId,
                    memberId,
                    new SearchQuestionCommand(
                            "결제 결정",
                            UUID.randomUUID()
                    )
            );
            if (followup) {
                SearchAnswerWorkResult old = executions.claim(accepted.answerMessageId())
                        .orElseThrow();
                executions.append(
                        old.answerId(),
                        old.token(),
                        "중지한 부분 답변"
                );
                executions.stop(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        old.answerId()
                );
                accepted = intake.acceptNext(
                        workspaceId,
                        memberId,
                        accepted.conversationId(),
                        new SearchQuestionCommand(
                                "결제 결정",
                                UUID.randomUUID()
                        )
                );
                assertThat(
                        executions.append(
                                old.answerId(),
                                old.token(),
                                "늦은 이전 출력"
                        )
                ).isFalse();
            }
            long answer = accepted.answerMessageId();
            worker.execute(answer);
            assertThat(
                    jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(String.class)
                            .single()
            ).isEqualTo("COMPLETED");
            assertThat(
                    jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(String.class)
                            .single()
            ).isEqualTo("김개발이 복구한다");
            assertThat(
                    jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(Long.class)
                            .list()
            ).containsExactly(document);
            assertThat(
                    jdbc.sql("SELECT excluded_count FROM search_answer_executions WHERE answer_id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(Integer.class)
                            .single()
            ).isZero();
            assertThat(turns.get()).isEqualTo(4);
            JsonNode generationMessages = mapper.readTree(server.requestBody())
                    .path("messages");
            assertThat(
                    generationMessages.get(0)
                            .path("content")
                            .asString()
            ).startsWith(SearchAnswerPrompt.system())
                    .contains(
                            "documentCreatedAt",
                            "recordingStartedAt"
                    );
            JsonNode generationInput = mapper.readTree(
                    generationMessages.get(1)
                            .path("content")
                            .asString()
            );
            assertThat(
                    generationInput.path("conversationHistory")
                            .isArray()
            ).isTrue();
            if (followup) {
                assertThat(
                        captured.getFirst()
                                .path("messages")
                                .toString()
                ).contains("중지한 부분 답변");
                assertThat(
                        generationInput.path("conversationHistory")
                                .toString()
                ).contains("중지한 부분 답변");
                assertThat(
                        generationInput.path("question")
                                .asString()
                ).isEqualTo("결제 결정");
                assertThat(
                        generationInput.path("conversationHistory")
                                .toString()
                ).doesNotContain("늦은 이전 출력");
            }
            assertThat(
                    generationInput.path("evidence")
                            .get(0)
                            .path("evidenceId")
                            .asString()
            ).isEqualTo("E" + document);
            assertThat(
                    generationInput.path("requestedAt")
                            .asString()
            ).contains("+09:00");
            assertThat(
                    generationInput.path("evidence")
                            .get(0)
                            .path("documentCreatedAt")
                            .asString()
            ).contains("+09:00");
            assertThat(
                    generationInput.path("evidence")
                            .get(0)
                            .path("recordingStartedAt")
                            .asString()
            ).contains("+09:00");
            assertThat(
                    generationInput.path("evidence")
                            .get(0)
                            .path("content")
                            .asString()
            ).contains("김개발");
        }
    }

    @Test
    @DisplayName("동시 MCP 요청은 Workspace의 권한과 본문을 섞지 않는다")
    void mcp_success_concurrentWorkspaceIsolation() throws Exception {
        long firstDocument = saveDocument(
                workspaceId,
                "ARCHIVED",
                "A",
                "첫 공간 비공개"
        );
        long other = fixtures.saveWorkspace();
        fixtures.join(
                other,
                memberId
        );
        long secondDocument = saveDocument(
                other,
                "ARCHIVED",
                "B",
                "둘 공간 비공개"
        );
        long answer = intake.acceptFirst(
                other,
                memberId,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        )
                .answerMessageId();
        SearchToolScope secondScope = SearchToolScope.from(
                executions.claim(answer)
                        .orElseThrow(),
                NOW,
                NOW.plusSeconds(120)
        );
        SearchToolTokenCodec codec = new SearchToolTokenCodec(
                mapper,
                properties,
                clock
        );
        String firstToken = codec.encode(
                "knot-search-mcp",
                scope
        );
        String secondToken = codec.encode(
                "knot-search-mcp",
                secondScope
        );
        try (java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<String> first = threads.submit(
                    () -> rpc(
                            firstToken,
                            "tools/call",
                            readArguments(firstDocument)
                    ).body()
            );
            java.util.concurrent.Future<String> second = threads.submit(
                    () -> rpc(
                            secondToken,
                            "tools/call",
                            readArguments(secondDocument)
                    ).body()
            );
            assertThat(first.get()).contains("첫 공간 비공개")
                    .doesNotContain("둘 공간 비공개");
            assertThat(second.get()).contains("둘 공간 비공개")
                    .doesNotContain("첫 공간 비공개");
        }
    }

    @Test
    void mcp_failure_foreignDocument() throws Exception {
        long document = saveDocument(
                fixtures.saveWorkspace(),
                "ARCHIVED",
                "외부",
                "다른 공간 비공개"
        );
        var response = rpc(
                bearer(scope),
                "tools/call",
                readArguments(document)
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("DOCUMENT_NOT_FOUND")
                .doesNotContain("다른 공간 비공개");
    }

    @Test
    void mcp_failure_tamperedToken() throws Exception {
        assertThat(
                rpc(
                        bearer(scope) + "x",
                        "tools/list",
                        "{}"
                ).statusCode()
        ).isEqualTo(401);
    }

    @Test
    void mcp_failure_expiredToken() throws Exception {
        SearchToolScope expired = new SearchToolScope(
                scope.workspaceId(),
                scope.memberId(),
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                scope.token(),
                NOW.minusSeconds(2),
                NOW.minusSeconds(1)
        );
        assertThat(
                rpc(
                        bearer(expired),
                        "tools/list",
                        "{}"
                ).statusCode()
        ).isEqualTo(401);
    }

    private String bearer(SearchToolScope value) {
        return new SearchToolTokenCodec(
                mapper,
                properties,
                clock
        ).encode(
                "knot-search-mcp",
                value
        );
    }

    @Test
    @DisplayName("후속 문맥은 현재 질문 전의 완료·중지 쌍만 제공한다")
    void history_success_completedAndStoppedBeforeQuestion() {
        jdbc.sql("UPDATE search_messages SET sequence = sequence + 8 WHERE conversation_id=:c")
                .param(
                        "c",
                        scope.conversationId()
                )
                .update();
        jdbc.sql("""
                INSERT INTO search_messages (conversation_id, role, sequence, content, status, created_at)
                SELECT :c, role, sequence, content, status, CURRENT_TIMESTAMP FROM (VALUES
                  ('USER',1,'이전 질문','RECEIVED'),('ASSISTANT',2,'완료 답변','COMPLETED'),
                  ('USER',3,'중지 질문','RECEIVED'),('ASSISTANT',4,'부분 답변','STOPPED'),
                  ('USER',5,'실패 질문','RECEIVED'),('ASSISTANT',6,'오류 부분','FAILED'),
                  ('USER',11,'미래 질문','RECEIVED'),('ASSISTANT',12,'미래 답변','COMPLETED')
                ) AS turns(role,sequence,content,status)
                """)
                .param(
                        "c",
                        scope.conversationId()
                )
                .update();
        assertThat(history.beforeQuestion(scope)).extracting(row -> row.get("content"))
                .containsExactly(
                        "이전 질문",
                        "완료 답변",
                        "중지 질문",
                        "부분 답변"
                );
        long otherWorkspace = fixtures.saveWorkspace();
        jdbc.sql("UPDATE search_conversations SET workspace_id = :workspace WHERE id = :conversation")
                .param(
                        "workspace",
                        otherWorkspace
                )
                .param(
                        "conversation",
                        scope.conversationId()
                )
                .update();
        assertThat(history.beforeQuestion(scope)).isEmpty();
        long otherMember = fixtures.saveMember("다른 대화 소유자");
        jdbc.sql(
                "UPDATE search_conversations SET workspace_id = :workspace, member_id = :member WHERE id = :conversation"
        )
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        otherMember
                )
                .param(
                        "conversation",
                        scope.conversationId()
                )
                .update();
        assertThat(history.beforeQuestion(scope)).isEmpty();
    }

    private String readArguments(long document) {
        return "{\"name\":\"read_document\",\"arguments\":{\"documentId\":" + document + "}}";
    }

    private String response(
            int turn,
            long document
    ) {
        if (turn == 1) {
            return toolTurn(
                    "t1",
                    "search_documents",
                    "{\"query\":\"결제\"}"
            );
        }
        if (turn == 2) {
            return toolTurn(
                    "t2",
                    "read_document",
                    "{\"documentId\":" + document + "}"
            );
        }
        if (turn == 3) {
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"탐색 완료\"}}]}";
        }
        if (turn == 4) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put(
                    "status",
                    "ANSWERED"
            );
            answer.put(
                    "decisionState",
                    "DECIDED"
            );
            answer.put(
                    "answer",
                    "김개발이 복구한다"
            );
            answer.put(
                    "evidenceIds",
                    List.of("E" + document)
            );
            answer.put(
                    "clarificationQuestion",
                    null
            );
            String json = mapper.writeValueAsString(answer);
            int split = json.indexOf("복구");
            return modelDelta(
                    json.substring(
                            0,
                            split
                    )
            ) + modelDelta(json.substring(split)) + "data: [DONE]\n\n";
        }
        throw new IllegalStateException("별도 근거 추출 호출은 사용하지 않는다");
    }

    private String modelDelta(String text) {
        return "data: " + mapper.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(
                                Map.of(
                                        "delta",
                                        Map.of(
                                                "content",
                                                text
                                        )
                                )
                        )
                )
        ) + "\n\n";
    }

    private String toolTurn(
            String id,
            String name,
            String arguments
    ) {
        return mapper.writeValueAsString(
                java.util.Map.of(
                        "choices",
                        java.util.List.of(
                                java.util.Map.of(
                                        "message",
                                        java.util.Map.of(
                                                "role",
                                                "assistant",
                                                "tool_calls",
                                                java.util.List.of(
                                                        java.util.Map.of(
                                                                "id",
                                                                id,
                                                                "type",
                                                                "function",
                                                                "function",
                                                                java.util.Map.of(
                                                                        "name",
                                                                        name,
                                                                        "arguments",
                                                                        arguments
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private java.net.http.HttpResponse<String> rpc(
            String bearer,
            String method,
            String params
    ) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/internal/mcp"))
                .header(
                        "Authorization",
                        "Bearer " + bearer
                )
                .header(
                        "Accept",
                        "application/json, text/event-stream"
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "MCP-Protocol-Version",
                        "2025-06-18"
                )
                .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"
                        )
                )
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(
                        request,
                        java.net.http.HttpResponse.BodyHandlers.ofString()
                );
    }

    private String searchCursor() {
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "결제 하나",
                "본문"
        );
        saveDocument(
                workspaceId,
                "ARCHIVED",
                "결제 둘",
                "본문"
        );
        String cursor = service.search(
                scope,
                parameters(
                        "결제",
                        null,
                        1
                )
        )
                .nextCursor();
        assertThat(cursor).isNotNull();
        return cursor;
    }

    @Test
    @DisplayName("문서 도구 권한 거절: finishedExecution")
    void list_failure_finishedExecution() {
        jdbc.sql("UPDATE search_answer_executions SET state = 'FINISHED' WHERE answer_id = :id")
                .param(
                        "id",
                        scope.answerId()
                )
                .update();
        SearchToolScope denied = scope;
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: wrongExecutionToken")
    void list_failure_wrongExecutionToken() {

        SearchToolScope denied = new SearchToolScope(
                workspaceId,
                memberId,
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                UUID.randomUUID(),
                scope.startedAt(),
                scope.expiresAt()
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: wrongQuestion")
    void list_failure_wrongQuestion() {

        SearchToolScope denied = new SearchToolScope(
                workspaceId,
                memberId,
                scope.conversationId(),
                scope.answerId(),
                scope.answerId(),
                scope.token(),
                scope.startedAt(),
                scope.expiresAt()
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: otherOwner")
    void list_failure_otherOwner() {
        long other = fixtures.saveMember("다른 멤버");
        fixtures.join(
                workspaceId,
                other
        );
        SearchToolScope denied = new SearchToolScope(
                workspaceId,
                other,
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                scope.token(),
                scope.startedAt(),
                scope.expiresAt()
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: otherWorkspace")
    void list_failure_otherWorkspace() {
        long other = fixtures.saveWorkspace();
        fixtures.join(
                other,
                memberId
        );
        SearchToolScope denied = new SearchToolScope(
                other,
                memberId,
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                scope.token(),
                scope.startedAt(),
                scope.expiresAt()
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: expiredScope")
    void list_failure_expiredScope() {
        when(clock.instant()).thenReturn(scope.expiresAt());
        SearchToolScope denied = scope;
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: futureStarted")
    void list_failure_futureStarted() {

        SearchToolScope denied = new SearchToolScope(
                workspaceId,
                memberId,
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                scope.token(),
                NOW.plusSeconds(1),
                scope.expiresAt()
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("문서 도구 권한 거절: deadlineExceeded")
    void list_failure_deadlineExceeded() {

        SearchToolScope denied = new SearchToolScope(
                workspaceId,
                memberId,
                scope.conversationId(),
                scope.questionId(),
                scope.answerId(),
                scope.token(),
                NOW,
                NOW.plusSeconds(86400)
        );
        assertThatThrownBy(
                () -> service.list(
                        denied,
                        parameters(
                                null,
                                null,
                                20
                        )
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchToolException.class)
                .hasMessage("SEARCH_ACCESS_DENIED");
    }

    private SearchDocumentParameters parameters(
            String queryText,
            String cursor,
            int size
    ) {
        return new SearchDocumentParameters(
                queryText,
                null,
                null,
                null,
                cursor,
                size
        );
    }

    private long saveDocument(
            long workspace,
            String status,
            String title,
            String content
    ) {
        long id = messages.saveDocument(
                workspace,
                memberId
        );
        jdbc.sql(
                "UPDATE documents SET title = :title, content = :content, status = :status, archived_at = CASE WHEN :status = 'ARCHIVED' THEN created_at END WHERE id = :id"
        )
                .param(
                        "title",
                        title
                )
                .param(
                        "content",
                        content
                )
                .param(
                        "status",
                        status
                )
                .param(
                        "id",
                        id
                )
                .update();
        return id;
    }
}
