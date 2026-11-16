package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.infrastructure.mcp.SearchMcpClient;
import com.knot.backend.testsupport.LogCapture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchMcpDocumentRetrieverTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .build();
    private final SearchLlmClient llm = mock(SearchLlmClient.class);
    private final SearchMcpClient mcp = mock(SearchMcpClient.class);
    private final SearchToolAccess access = mock(SearchToolAccess.class);
    private final SearchHistoryQuery history = mock(SearchHistoryQuery.class);
    private final SearchAgentProperties properties = new SearchAgentProperties();
    private SearchMcpDocumentRetriever retriever;
    private SearchToolScope scope;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        scope = new SearchToolScope(
                1,
                1,
                1,
                1,
                2,
                UUID.randomUUID(),
                now,
                now.plusSeconds(120)
        );
        when(mcp.definitions(scope)).thenReturn(List.of());
        when(history.beforeQuestion(scope)).thenReturn(List.of());
        retriever = new SearchMcpDocumentRetriever(
                llm,
                mcp,
                history,
                access,
                properties,
                mapper,
                mock(SearchFastPathDocumentRetriever.class)
        );
    }

    @Test
    @DisplayName("검색 실행기는 자유 답변 도구를 노출하거나 실행하지 않는다")
    void retrieve_failure_directToolForbidden() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "direct",
                        "answer_without_search",
                        "{\"answer\":\"케이크 레시피\"}"
                )
        );
        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "회의 결정"
                )
        ).hasMessage("INVALID_MODEL_TOOL_CALL");
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> tools = org.mockito.ArgumentCaptor.captor();
        verify(llm).complete(
                eq(scope),
                any(),
                tools.capture()
        );
        assertThat(
                tools.getValue()
                        .toString()
        ).doesNotContain("answer_without_search");
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("첫 탐색에서 도구 없는 자유 문장은 재시도 없이 실패한다")
    void retrieve_failure_noToolCallsOnce() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"role\":\"assistant\",\"content\":\"케이크 레시피\"}"));
        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "회의 결정"
                )
        ).hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
        verify(llm).complete(
                eq(scope),
                any(),
                any()
        );
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("문서 탐색을 시작한 뒤 직접 응답으로 근거 검증을 우회할 수 없다")
    void retrieve_failure_directAnswerAfterSearch() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "search",
                        "search_documents",
                        "{\"query\":\"회의\"}"
                ),
                turn(
                        "direct",
                        "answer_without_search",
                        "{\"answer\":\"회의에서 승인했습니다.\"}"
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[{\"documentId\":1}]}"));

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "회의 결정"
                )
        ).hasMessage("INVALID_MODEL_TOOL_CALL");
        verify(
                mcp,
                never()
        ).call(
                eq(scope),
                eq("answer_without_search"),
                any()
        );
    }

    @Test
    @DisplayName("비어 있는 직접 응답은 정상 완료로 처리하지 않는다")
    void retrieve_failure_blankDirectAnswer() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "greeting",
                        "answer_without_search",
                        "{\"answer\":\"  \"}"
                )
        );

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "반갑다"
                )
        ).hasMessage("INVALID_MODEL_TOOL_CALL");
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"answer\":null}", "{\"answer\":1}", "[]"})
    @DisplayName("직접 응답 인자가 객체와 문자열 답변 계약을 어기면 실패한다")
    void retrieve_failure_invalidDirectAnswerArguments(String arguments) {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "greeting",
                        "answer_without_search",
                        arguments
                )
        );

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "반갑다"
                )
        ).hasMessage("INVALID_MODEL_TOOL_CALL");
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("첫 모델 호출 오류를 임의 응답으로 완료하지 않는다")
    void retrieve_failure_modelTransportError() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenThrow(new SearchToolException("MODEL_TRANSPORT_FAILED"));

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "반갑다"
                )
        ).hasMessage("MODEL_TRANSPORT_FAILED");
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("검색 도구 결과가 비어 있으면 기존 결과 없음 판정을 적용한다")
    void retrieve_success_emptyDocumentSearch() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "search",
                        "search_documents",
                        "{\"query\":\"회의\"}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"끝\"}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "회의 결정"
        );

        assertThat(result.documents()).isEmpty();
        verify(
                llm,
                times(2)
        ).complete(
                eq(scope),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("응답 경로 재선택에도 도구 없이 종료하면 임의 문장을 답변으로 사용하지 않는다")
    void retrieve_failure_unroutedAnswerAfterRetry() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"role\":\"assistant\",\"content\":\"회의에서 승인했습니다.\",\"tool_calls\":[]}"));

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "회의 결정"
                )
        ).hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
        verify(
                llm,
                times(1)
        ).complete(
                eq(scope),
                any(),
                any()
        );
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("직접 응답과 문서 검색을 동시에 호출하면 두 경로를 모두 실행하지 않고 실패한다")
    void retrieve_failure_mixedDirectAnswerAndDocumentTools() {
        JsonNode response = mapper.valueToTree(
                Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(
                                turn(
                                        "greeting",
                                        "answer_without_search",
                                        "{\"answer\":\"반갑습니다\"}"
                                ).path("tool_calls")
                                        .get(0),
                                turn(
                                        "search",
                                        "search_documents",
                                        "{\"query\":\"회의\"}"
                                ).path("tool_calls")
                                        .get(0)
                        )
                )
        );
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(response);

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "안녕, 회의 결정 알려줘"
                )
        ).hasMessage("INVALID_MODEL_TOOL_CALL");
        verify(
                mcp,
                never()
        ).call(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("검색 후보는 근거로 넘기지 않고 실제 읽은 본문만 생성기에 전달한다")
    void retrieve_success_readDocumentsOnly() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "search_documents",
                        "{\"query\":\"결정\"}"
                ),
                turn(
                        "t2",
                        "read_document",
                        "{\"documentId\":1}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"탐색 종료\"}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[{\"documentId\":1},{\"documentId\":2}],\"nextCursor\":null}"));
        when(
                mcp.call(
                        eq(scope),
                        eq("read_document"),
                        any()
                )
        ).thenReturn(
                mapper.readTree(
                        "{\"document\":{\"documentId\":1,\"title\":\"회의\",\"topic\":\"결정\",\"contentRevision\":\"r1\"},\"content\":\"실제 읽은 본문\",\"offset\":0,\"truncated\":false,\"nextCursor\":null}"
                )
        );
        com.knot.backend.search.application.dto.result.SearchRetrievalResult result = retriever.retrieve(
                scope,
                "결정"
        );
        assertThat(result.documents()).hasSize(1);
        assertThat(
                result.documents()
                        .getFirst()
                        .content()
        ).isEqualTo("실제 읽은 본문");
        assertThat(result.excludedCount()).isZero();
    }

    @Test
    @DisplayName("다음 탐색 요청에는 기본 문맥과 가장 최근 도구 결과만 전달한다")
    void retrieve_successRetainsLatestToolExchange() {
        String searchMarker = "previous-search-result".repeat(100);
        String listMarker = "previous-list-result".repeat(40);
        List<List<Map<String, Object>>> requests = new ArrayList<>();
        int[] responseIndex = {0};
        JsonNode[] responses = {turnWithCalls(
                List.of(
                        toolCall(
                                "search",
                                "search_documents",
                                "{\"query\":\"결정\"}"
                        ),
                        toolCall(
                                "list",
                                "list_documents",
                                "{\"topic\":\"결정\"}"
                        )
                )
        ), turn("read",
                "read_document",
                "{\"documentId\":1}"
        ), mapper.readTree("{\"role\":\"assistant\",\"content\":\"탐색 완료\"}")};
        when(history.beforeQuestion(scope)).thenReturn(
                List.of(
                        Map.of(
                                "role",
                                "user",
                                "content",
                                "이전 질문"
                        ),
                        Map.of(
                                "role",
                                "assistant",
                                "content",
                                "이전 답변"
                        )
                )
        );
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(1);
            requests.add(new ArrayList<>(messages));
            return responses[responseIndex[0]++];
        });
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(
                mapper.valueToTree(
                        Map.of(
                                "items",
                                List.of(
                                        Map.of(
                                                "documentId",
                                                1,
                                                "snippet",
                                                searchMarker
                                        )
                                )
                        )
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(
                mapper.valueToTree(
                        Map.of(
                                "items",
                                List.of(
                                        Map.of(
                                                "documentId",
                                                2,
                                                "snippet",
                                                listMarker
                                        )
                                )
                        )
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("read_document"),
                        any()
                )
        ).thenReturn(
                mapper.readTree(
                        "{\"document\":{\"documentId\":1,\"title\":\"회의\",\"topic\":\"결정\",\"contentRevision\":\"r1\"},\"content\":\"최신 읽기 결과\",\"offset\":0,\"truncated\":false,\"nextCursor\":null}"
                )
        );

        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "현재 질문"
        );

        assertThat(requests).hasSize(3);
        assertThat(mapper.writeValueAsString(requests.get(1))).contains(searchMarker)
                .contains(listMarker);
        assertThat(mapper.writeValueAsString(requests.get(2))).contains("현재 질문")
                .contains("이전 질문")
                .contains("이전 답변")
                .contains("최신 읽기 결과")
                .doesNotContain(searchMarker)
                .doesNotContain(listMarker);
        assertThat(result.documents()).hasSize(1);
        assertThat(
                result.documents()
                        .getFirst()
                        .content()
        ).isEqualTo("최신 읽기 결과");
    }

    @Test
    @DisplayName("검색에 성공했지만 후보를 읽지 않으면 결과 없음으로 완료하지 않는다")
    void retrieve_failure_unreadCandidates() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"끝\"}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[{\"documentId\":1}]}"));
        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "질문"
                )
        ).hasMessage("INCOMPLETE_DOCUMENT_EXPLORATION");
    }

    @Test
    @DisplayName("검색 결과 없이 전체 목록만 발견하면 모델의 자유 답변 대신 빈 근거를 반환한다")
    void retrieve_success_noSearchMatchesWithListedDocuments() {
        // given
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "search",
                        "search_documents",
                        "{\"query\":\"점심\"}"
                ),
                turn(
                        "list",
                        "list_documents",
                        "{}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"모델이 임의로 작성한 답변\",\"tool_calls\":[]}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[{\"documentId\":1,\"title\":\"결제 결정\"}]}"));

        // when
        SearchRetrievalResult result = retriever.retrieve(
                scope,
                "점심"
        );

        // then
        assertThat(result.documents()).isEmpty();
        assertThat(result.excludedCount()).isZero();
        verify(
                mcp,
                never()
        ).call(
                eq(scope),
                eq("read_document"),
                any()
        );
    }

    @Test
    @DisplayName("검색 도구 오류는 결과 없음으로 변환하지 않고 그대로 실패한다")
    void retrieve_failure_searchToolError() {
        // given
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "search",
                        "search_documents",
                        "{\"query\":\"점심\"}"
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenThrow(new SearchToolException("SEARCH_TRANSPORT_FAILED"));

        // when & then
        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "점심"
                )
        ).hasMessage("SEARCH_TRANSPORT_FAILED");
    }

    @Test
    @DisplayName("탐색 회차와 도구 결과 개수만 기록하고 질문과 도구 인자는 남기지 않는다")
    void retrieve_success_logsToolFlowWithoutInput() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{\"topic\":\"private-topic\"}"
                ),
                turn(
                        "t2",
                        "search_documents",
                        "{\"query\":\"private-query\"}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"private-model-output\",\"tool_calls\":[]}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[{\"documentId\":1,\"title\":\"private-title\"}]}"));
        when(
                mcp.call(
                        eq(scope),
                        eq("search_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));

        try (LogCapture logs = new LogCapture(SearchMcpDocumentRetriever.class)) {
            SearchRetrievalResult result = retriever.retrieve(
                    scope,
                    "private-question"
            );

            assertThat(result.documents()).isEmpty();
            assertThat(logs.events()).filteredOn(
                    event -> event.getFormattedMessage()
                            .contains("Search exploration tool_call")
            )
                    .hasSize(2)
                    .anySatisfy(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "round=1",
                                    "toolCallIndex=1",
                                    "toolName=list_documents",
                                    "repeated=false",
                                    "resultCount=1",
                                    "outcome=success"
                            )
                    )
                    .anySatisfy(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "round=2",
                                    "toolCallIndex=2",
                                    "toolName=search_documents",
                                    "repeated=false",
                                    "resultCount=0",
                                    "outcome=success"
                            )
                    )
                    .allSatisfy(
                            event -> assertThat(event.getFormattedMessage()).doesNotContain(
                                    "private-question",
                                    "private-topic",
                                    "private-query",
                                    "private-title",
                                    "private-model-output"
                            )
                    );
        }
    }

    @Test
    @DisplayName("LLM 탐색 호출 실패는 실패한 회차와 안전한 오류 코드만 기록한다")
    void retrieve_failure_logsExplorationRound() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenThrow(
                new SearchToolException(
                        "LLM_TIMEOUT",
                        new IllegalStateException("private-provider-message")
                )
        );

        try (LogCapture logs = new LogCapture(SearchMcpDocumentRetriever.class)) {
            assertThatThrownBy(
                    () -> retriever.retrieve(
                            scope,
                            "private-question"
                    )
            ).hasMessage("LLM_TIMEOUT");

            assertThat(logs.events()).filteredOn(
                    event -> event.getFormattedMessage()
                            .contains("Search exploration llm_round")
            )
                    .singleElement()
                    .satisfies(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "round=1",
                                    "outcome=failure",
                                    "failureCode=LLM_TIMEOUT"
                            )
                                    .doesNotContain(
                                            "private-question",
                                            "private-provider-message"
                                    )
                    );
        }
    }

    @Test
    @DisplayName("같은 도구 입력을 반복하면 모델에 입력 변경 또는 탐색 종료를 안내한다")
    void retrieve_success_repeatedToolCallReturnsGuidance() {
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> messages = org.mockito.ArgumentCaptor.captor();
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{}"
                ),
                turn(
                        "t2",
                        "list_documents",
                        "{}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"탐색 완료\"}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));

        SearchRetrievalResult result;
        try (LogCapture logs = new LogCapture(SearchMcpDocumentRetriever.class)) {
            result = retriever.retrieve(
                    scope,
                    "질문"
            );
            assertThat(logs.events()).filteredOn(
                    event -> event.getFormattedMessage()
                            .contains("Search exploration tool_call")
            )
                    .hasSize(2)
                    .anySatisfy(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "round=1",
                                    "toolCallIndex=1",
                                    "toolName=list_documents",
                                    "repeated=false",
                                    "resultCount=0",
                                    "outcome=success"
                            )
                    )
                    .anySatisfy(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "round=2",
                                    "toolCallIndex=2",
                                    "toolName=list_documents",
                                    "repeated=true",
                                    "resultCount=0",
                                    "outcome=repeated"
                            )
                    );
        }

        assertThat(result.documents()).isEmpty();
        verify(
                llm,
                times(3)
        ).complete(
                eq(scope),
                messages.capture(),
                any()
        );
        assertThat(
                messages.getAllValues()
                        .get(2)
        ).anySatisfy(message -> {
            assertThat(message).containsEntry(
                    "role",
                    "tool"
            )
                    .containsEntry(
                            "tool_call_id",
                            "t2"
                    );
            assertThat(
                    message.get("content")
                            .toString()
            ).contains("REPEATED_TOOL_CALL")
                    .contains("already been executed")
                    .contains("Change its input or finish");
        });
        verify(
                mcp,
                times(1)
        ).call(
                eq(scope),
                eq("list_documents"),
                any()
        );
    }

    @Test
    @DisplayName("반복 도구 호출도 전체 도구 호출 한도에 포함한다")
    void retrieve_failure_repeatedToolCallCountsTowardLimit() {
        properties.setToolCallLimit(2);
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> messages = org.mockito.ArgumentCaptor.captor();
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{}"
                ),
                turn(
                        "t2",
                        "list_documents",
                        "{}"
                ),
                turn(
                        "t3",
                        "list_documents",
                        "{}"
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));

        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "질문"
                )
        ).hasMessage("SEARCH_TOOL_LIMIT_EXCEEDED");

        verify(
                llm,
                times(3)
        ).complete(
                eq(scope),
                messages.capture(),
                any()
        );
        assertThat(
                messages.getAllValues()
                        .get(2)
        ).anySatisfy(message -> {
            assertThat(message).containsEntry(
                    "role",
                    "tool"
            )
                    .containsEntry(
                            "tool_call_id",
                            "t2"
                    );
            assertThat(
                    message.get("content")
                            .toString()
            ).contains("REPEATED_TOOL_CALL");
        });
        verify(
                mcp,
                times(1)
        ).call(
                eq(scope),
                eq("list_documents"),
                any()
        );
    }

    @Test
    void retrieve_success_exactToolLimit() {
        properties.setToolCallLimit(1);
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"끝\"}")
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));
        assertThat(
                retriever.retrieve(
                        scope,
                        "질문"
                )
                        .documents()
        ).isEmpty();
        org.mockito.Mockito.verify(mcp)
                .call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                );
    }

    @Test
    void retrieve_failure_toolLimitPlusOne() {
        properties.setToolCallLimit(1);
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{}"
                ),
                turn(
                        "t2",
                        "list_documents",
                        "{\"cursor\":\"next\"}"
                )
        );
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenReturn(mapper.readTree("{\"items\":[]}"));
        assertThatThrownBy(
                () -> retriever.retrieve(
                        scope,
                        "질문"
                )
        ).hasMessage("SEARCH_TOOL_LIMIT_EXCEEDED");
        org.mockito.Mockito.verify(mcp)
                .call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                );
    }

    @Test
    void retrieve_success_largeWorkspaceUsesBoundedPages() {
        when(
                llm.complete(
                        eq(scope),
                        any(),
                        any()
                )
        ).thenReturn(
                turn(
                        "t1",
                        "list_documents",
                        "{\"size\":100}"
                ),
                turn(
                        "t2",
                        "read_document",
                        "{\"documentId\":1}"
                ),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"끝\"}")
        );
        List<Map<String, Object>> ninety = java.util.stream.IntStream.rangeClosed(
                1,
                90
        )
                .mapToObj(
                        id -> Map.<String, Object>of(
                                "documentId",
                                id,
                                "title",
                                "회의",
                                "snippet",
                                "문서".repeat(200)
                        )
                )
                .toList();
        when(
                mcp.call(
                        eq(scope),
                        eq("list_documents"),
                        any()
                )
        ).thenAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(2);
            int size = ((Number) arguments.get("size")).intValue();
            return mapper.valueToTree(
                    Map.of(
                            "items",
                            ninety.subList(
                                    0,
                                    size
                            ),
                            "nextCursor",
                            "next-page"
                    )
            );
        });
        when(
                mcp.call(
                        eq(scope),
                        eq("read_document"),
                        any()
                )
        ).thenReturn(
                mapper.readTree(
                        "{\"document\":{\"documentId\":1,\"title\":\"회의\",\"topic\":\"결정\",\"contentRevision\":\"r1\"},\"content\":\"읽은 본문\",\"offset\":0,\"truncated\":false}"
                )
        );
        assertThat(
                retriever.retrieve(
                        scope,
                        "결정"
                )
                        .documents()
        ).hasSize(1);
        org.mockito.Mockito.verify(mcp)
                .call(
                        scope,
                        "list_documents",
                        Map.of(
                                "size",
                                20
                        )
                );
    }

    private JsonNode turn(
            String id,
            String name,
            String arguments
    ) {
        return turnWithCalls(
                List.of(
                        toolCall(
                                id,
                                name,
                                arguments
                        )
                )
        );
    }

    private JsonNode turnWithCalls(List<Map<String, Object>> calls) {
        return mapper.valueToTree(
                Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        calls
                )
        );
    }

    private Map<String, Object> toolCall(
            String id,
            String name,
            String arguments
    ) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        name,
                        "arguments",
                        arguments
                )
        );
    }
}
