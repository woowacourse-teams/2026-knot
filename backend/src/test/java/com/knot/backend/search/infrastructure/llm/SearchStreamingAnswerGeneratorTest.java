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
        assertThatThrownBy(
                () -> generator.generate(
                        scope,
                        "변화",
                        documents(),
                        text -> {
                        }
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
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
                    "핵심 원칙: 근거에 없는 내용은 답변하지 않습니다.",
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
    @DisplayName("모델 스트림이 끊기면 JSON 외피 없이 이미 받은 답변만 보존한다")
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
        assertThatThrownBy(
                () -> generator.generate(
                        scope,
                        "변화",
                        documents(),
                        deltas::add
                )
        ).hasMessage("MODEL_GENERATION_FAILED");
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("부분 답변");
    }

    @Test
    @DisplayName("입력 한도로 제외된 문서를 모델이 인용하면 실패한다")
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
        assertThatThrownBy(
                () -> generator.generate(
                        scope,
                        "어제 회의",
                        large,
                        text -> {
                        }
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
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
