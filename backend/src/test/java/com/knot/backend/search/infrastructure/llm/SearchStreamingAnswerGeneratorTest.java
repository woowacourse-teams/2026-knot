package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

class SearchStreamingAnswerGeneratorTest {

    @Test
    @DisplayName("잘못된 형식은 한 번 복구하고 검증 전 글자는 화면에 전달하지 않는다")
    void generate_success_repairsBeforePublishing() {
        List<String> deltas = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(deltas).isEmpty();
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(attempts.incrementAndGet() == 1 ? "{\"answer\":\"검증되지 않은 내용\"}" : response(List.of("E1")));
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        assertThat(
                generator.generate(
                        scope,
                        "질문",
                        documents(),
                        deltas::add
                )
        ).containsExactly(1L);
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("세 회의의 변화");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SEARCH_REQUEST_REJECTED", "SEARCH_RATE_LIMITED",
            "SEARCH_TRANSPORT_TIMEOUT"})
    @DisplayName("요청 거절과 제한·기한 오류는 반복하지 않고 관련 기록을 안내한다")
    void generate_success_rejectedRequestGuidance(String code) {
        org.mockito.Mockito.doThrow(new SearchToolException(code))
                .when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        List<String> deltas = new ArrayList<>();
        assertThat(
                generator.generate(
                        scope,
                        "질문",
                        documents(),
                        deltas::add
                )
        ).containsExactly(
                1L,
                2L,
                3L
        );
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isNotBlank()
                .doesNotContain("실패");
        verify(llm).stream(
                eq(scope),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("일시적인 공급자 오류는 한 번만 복구한다")
    void generate_success_retriesTransientOnce() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1)
                throw new SearchToolException("SEARCH_UPSTREAM_UNAVAILABLE");
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(response(List.of("E1")));
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        assertThat(
                generator.generate(
                        scope,
                        "질문",
                        documents(),
                        text -> {
                        }
                )
        ).containsExactly(1L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("생성 장애의 문서 안내는 본문에서 실제 선택한 문서만 이름으로 제시한다")
    void generate_success_guidanceNamesUsedDocuments() {
        org.mockito.Mockito.doThrow(new SearchToolException("SEARCH_REQUEST_REJECTED"))
                .when(llm).stream(eq(scope), any(), any());
        List<String> deltas = new ArrayList<>();
        List<SearchDocumentResult> docs = List.of(
                new SearchDocumentResult(1L, "발표 일정", "데모", "루덴스가 3분 발표한다."),
                new SearchDocumentResult(2L, "질의응답", "데모", "2분 질문을 받는다."));
        assertThat(generator.generate(scope, "데모", docs, deltas::add)).containsExactly(1L, 2L);
        assertThat(String.join("", deltas)).contains("발표 일정", "질의응답").doesNotContain("루덴스가 3분", "2분 질문");
    }

    @Test
    @DisplayName("권한 오류는 복구 안내나 일반 답변으로 우회하지 않는다")
    void generate_failure_accessDenied() {
        org.mockito.Mockito.doThrow(new SearchToolException("SEARCH_ACCESS_DENIED"))
                .when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(
                () -> generator.generate(
                        scope,
                        "질문",
                        documents(),
                        deltas::add
                )
        ).hasMessage("SEARCH_ACCESS_DENIED");
        assertThat(deltas).isEmpty();
        verify(llm).stream(
                eq(scope),
                any(),
                any()
        );
    }

    private final SearchLlmClient llm = mock(SearchLlmClient.class);
    private final SearchHistoryQuery history = mock(SearchHistoryQuery.class);
    private final SearchToolAccess access = mock(SearchToolAccess.class);
    private final JsonMapper mapper = JsonMapper.builder()
            .build();
    private SearchToolScope scope;
    private SearchStreamingAnswerGenerator generator;

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
        when(history.beforeQuestion(scope)).thenReturn(List.of());
        generator = new SearchStreamingAnswerGenerator(
                llm,
                history,
                access,
                new SearchAgentProperties(),
                mapper
        );
        doAnswer(call -> {
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(
                    response(
                            List.of(
                                    "E4",
                                    "E2",
                                    "E1"
                            )
                    )
            );
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("JSON의 답변만 전달하고 실제 참조 근거 최대 세 개를 돌려준다")
    void generate_success_structuredAnswer() {
        List<String> deltas = new ArrayList<>();
        assertThat(
                generator.generate(
                        scope,
                        "변화",
                        documents(),
                        deltas::add
                )
        ).containsExactly(
                4L,
                2L,
                1L
        );
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("세 회의의 변화");
        verify(
                llm,
                never()
        ).evidence(
                eq(scope),
                any()
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"E99", "E1,E1"})
    @DisplayName("읽지 않은 ID 또는 중복 ID는 실제 사용 근거로 인정하지 않는다")
    void generate_failure_invalidEvidence(String ids) {
        doAnswer(call -> {
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(response(List.of(ids.split(","))));
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        List<String> deltas = new ArrayList<>();
        assertThat(
                generator.generate(
                        scope,
                        "변화",
                        documents(),
                        deltas::add
                )
        ).containsExactly(
                1L,
                2L,
                3L
        );
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo(com.knot.backend.search.application.SearchAnswerGuidance.RELATED_DOCUMENTS);
        verify(
                llm,
                org.mockito.Mockito.times(2)
        ).stream(
                eq(scope),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("지정 시스템 프롬프트와 구분된 대화 문맥·근거를 모델 요청에 전달한다")
    void generate_success_promptAndEvidenceBoundary() {
        List<Map<String, Object>> previous = List.of(
                Map.of(
                        "role",
                        "assistant",
                        "content",
                        "이전 답변"
                )
        );
        when(history.beforeQuestion(scope)).thenReturn(previous);
        doAnswer(call -> {
            List<Map<String, Object>> messages = call.getArgument(1);
            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst()).containsEntry(
                    "role",
                    "system"
            );
            assertThat(
                    messages.getFirst()
                            .get("content")
                            .toString()
            ).startsWith(SearchAnswerPrompt.system());
            String system = (String) messages.getFirst()
                    .get("content");
            assertThat(system).contains(
                    "핵심 원칙: 회의 기록에 관한 사실은 제공된 근거에서만 답변합니다.",
                    "제안을 결정으로 바꾸지 않습니다.",
                    "서로 다른 출처 문서는 최대 3개로 제한합니다."
            );
            JsonNode user = mapper.readTree(
                    (String) messages.getLast()
                            .get("content")
            );
            assertThat(
                    user.path("question")
                            .asString()
            ).isEqualTo("변화");
            assertThat(
                    user.path("conversationHistory")
                            .get(0)
                            .path("content")
                            .asString()
            ).isEqualTo("이전 답변");
            assertThat(
                    user.path("evidence")
                            .get(0)
                            .path("evidenceId")
                            .asString()
            ).isEqualTo("E1");
            assertThat(
                    user.path("evidence")
                            .get(0)
                            .path("documentId")
                            .asLong()
            ).isEqualTo(1L);
            assertThat(
                    user.path("evidence")
                            .get(0)
                            .path("content")
                            .asString()
            ).isEqualTo("읽은 본문");
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(response(List.of("E1")));
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        assertThat(
                generator.generate(
                        scope,
                        "변화",
                        documents(),
                        text -> {
                        }
                )
        ).containsExactly(1L);
    }

    @Test
    @DisplayName("모델 스트림이 끊기면 미검증 부분 답변 없이 실제 기록만 안내한다")
    void generate_failure_interruptedStreamPreservesPartialAnswer() {
        List<String> deltas = new ArrayList<>();
        doAnswer(call -> {
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept("{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"부분 답변");
            throw new SearchToolException("MODEL_GENERATION_FAILED");
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        assertThat(
                generator.generate(
                        scope,
                        "변화",
                        documents(),
                        deltas::add
                )
        ).containsExactly(
                1L,
                2L,
                3L
        );
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo(com.knot.backend.search.application.SearchAnswerGuidance.RELATED_DOCUMENTS)
                .doesNotContain("부분 답변");
        verify(
                llm,
                org.mockito.Mockito.times(2)
        ).stream(
                eq(scope),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("입력 한도로 제외된 문서는 복구 안내에서도 인용하지 않는다")
    void generate_failure_rejectsEvidenceExcludedByBudget() {
        // given
        SearchAgentProperties properties = new SearchAgentProperties();
        properties.setContextCharacterLimit(8000);
        generator = new SearchStreamingAnswerGenerator(
                llm,
                history,
                access,
                properties,
                mapper
        );
        List<SearchDocumentResult> large = List.of(
                new SearchDocumentResult(
                        1,
                        "첫 문서",
                        "주제",
                        "내용".repeat(4000)
                ),
                new SearchDocumentResult(
                        2,
                        "둘째 문서",
                        "주제",
                        "내용".repeat(4000)
                )
        );
        doAnswer(call -> {
            List<Map<String, Object>> messages = call.getArgument(1);
            assertThat(
                    mapper.writeValueAsString(messages)
                            .length()
            ).isLessThanOrEqualTo(8000);
            assertThat(
                    messages.getLast()
                            .get("content")
                            .toString()
            ).contains("E1")
                    .doesNotContain("E2");
            Consumer<String> consumer = call.getArgument(2);
            consumer.accept(response(List.of("E2")));
            return null;
        }).when(llm)
                .stream(
                        eq(scope),
                        any(),
                        any()
                );
        // when & then
        assertThat(
                generator.generate(
                        scope,
                        "어제 회의",
                        large,
                        text -> {
                        }
                )
        ).containsExactly(1L);
    }

    private String response(List<String> ids) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(
                "status",
                "ANSWERED"
        );
        json.put(
                "decisionState",
                "DECIDED"
        );
        json.put(
                "answer",
                "세 회의의 변화"
        );
        json.put(
                "evidenceIds",
                ids
        );
        json.put(
                "clarificationQuestion",
                null
        );
        return mapper.writeValueAsString(json);
    }

    private List<SearchDocumentResult> documents() {
        return java.util.stream.LongStream.rangeClosed(
                1,
                4
        )
                .mapToObj(
                        id -> new SearchDocumentResult(
                                id,
                                "회의",
                                "결정",
                                "읽은 본문"
                        )
                )
                .toList();
    }
}
