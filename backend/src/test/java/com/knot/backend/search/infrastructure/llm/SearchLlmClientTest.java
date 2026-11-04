package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.testsupport.LlmHttpServer;
import com.knot.backend.testsupport.LogCapture;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchLlmClientTest {

    @Test
    @DisplayName("모델 호출은 안전한 입력 크기와 provider 토큰 사용량만 기록한다")
    void complete_success_logsCallTimingAndUsageWithoutPrompt() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}"
            );
            try (LogCapture logs = new LogCapture(SearchLlmClient.class)) {
                client(
                        server,
                        http
                ).complete(
                        scope(),
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "private-question"
                                )
                        ),
                        List.of()
                );
                assertThat(logs.events()).singleElement()
                        .satisfies(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.INFO);
                            assertThat(event.getFormattedMessage()).contains(
                                    "phase=llm_call",
                                    "operation=complete",
                                    "answerId=2",
                                    "messageContentChars=16",
                                    "promptTokens=11",
                                    "completionTokens=7",
                                    "outcome=success"
                            )
                                    .doesNotContain("private-question");
                        });
            }
        }
    }

    @Test
    @DisplayName("의도 분류는 추론을 끄고 제한된 JSON Schema를 한 번 요청한다")
    void intent_success_strictRequest() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"intent\\\":\\\"MEETING_SEARCH\\\"}\"}}]}"
            );
            client(
                    server,
                    http
            ).intent(
                    scope(),
                    List.of()
            );
            JsonNode body = JsonMapper.builder()
                    .build()
                    .readTree(server.requestBody());
            assertThat(
                    body.path("stream")
                            .asBoolean(true)
            ).isFalse();
            assertThat(
                    body.path("max_tokens")
                            .asInt()
            ).isEqualTo(128);
            assertThat(
                    body.path("temperature")
                            .asInt(-1)
            ).isZero();
            assertThat(
                    body.path("reasoning_effort")
                            .asString()
            ).isEqualTo("none");
            assertThat(body.has("tools")).isFalse();
            assertThat(
                    body.path("response_format")
                            .path("json_schema")
                            .path("schema")
                            .path("properties")
                            .path("intent")
                            .path("enum")
            ).hasSize(5);
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\",\"tool_calls\":[{\"id\":\"call-1\"}]}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"user\",\"content\":\"{}\"}}]}"})
    @DisplayName("의도 분류의 비정상 종료나 도구 호출은 정상 의도로 수용하지 않는다")
    void intent_failure_invalidEnvelope(String response) throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    response
            );
            assertThatThrownBy(
                    () -> client(
                            server,
                            http
                    ).intent(
                            scope(),
                            List.of()
                    )
            ).isInstanceOf(SearchToolException.class);
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null"})
    @DisplayName("LM Studio의 비어 있는 tool_calls 메타데이터는 실제 도구 호출로 취급하지 않는다")
    void intent_success_emptyToolCalls(String tools) throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    """
                            {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                            "content":"{\\"intent\\":\\"MEETING_SEARCH\\"}","tool_calls":%s}}]}
                            """.formatted(tools)
            );
            assertThat(
                    client(
                            server,
                            http
                    ).intent(
                            scope(),
                            List.of()
                    )
                            .path("content")
                            .asString()
            ).contains("MEETING_SEARCH");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private final SearchTransportCancellation cancellation = new SearchTransportCancellation(
            mock(SearchToolAccess.class),
            Duration.ofMillis(250)
    );

    @AfterEach
    void tearDown() {
        cancellation.close();
    }

    @Test
    @DisplayName("도구 호출 요청은 모델 tools를 전달하고 공급자의 tool_calls를 그대로 돌려준다")
    void complete_success_nativeToolCalls() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"list_documents\",\"arguments\":\"{}\"}}]}}]}"
            );
            SearchLlmClient client = client(
                    server,
                    http
            );
            assertThat(
                    client.complete(
                            scope(),
                            List.of(
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            "질문"
                                    )
                            ),
                            List.of(
                                    Map.of(
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    "list_documents"
                                            )
                                    )
                            )
                    )
                            .path("tool_calls")
            ).hasSize(1);
            assertThat(server.requestBody()).contains(
                    "tools",
                    "list_documents",
                    "질문"
            );
            assertThat(server.authorization()).isEqualTo("Bearer test-token");
        }
    }

    @Test
    @DisplayName("공급자의 SSE를 UTF8 delta로 전달한다")
    void stream_success_utf8() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"한글 😀\"}}]}\n\ndata: [DONE]\n\n"
            );
            List<String> parts = new ArrayList<>();
            client(
                    server,
                    http
            ).stream(
                    scope(),
                    List.of(
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    "질문"
                            )
                    ),
                    parts::add
            );
            assertThat(parts).containsExactly("한글 😀");
            assertThat(server.requestBody()).contains("\"stream\":true");
        }
    }

    @Test
    @DisplayName("답변 스트림 요청에 근거 개수와 상태를 제한하는 JSON Schema를 포함한다")
    void stream_success_requestsAnswerSchema() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
            );
            client(
                    server,
                    http
            ).stream(
                    scope(),
                    List.of(),
                    text -> {
                    }
            );
            JsonNode format = JsonMapper.builder()
                    .build()
                    .readTree(server.requestBody())
                    .path("response_format");
            JsonNode schema = format.path("json_schema")
                    .path("schema");
            assertThat(
                    format.path("type")
                            .asString()
            ).isEqualTo("json_schema");
            assertThat(
                    format.path("json_schema")
                            .path("strict")
                            .asBoolean()
            ).isTrue();
            assertThat(
                    schema.path("additionalProperties")
                            .asBoolean(true)
            ).isFalse();
            assertThat(schema.path("required")).hasSize(5);
            assertThat(
                    schema.path("properties")
                            .path("evidenceIds")
                            .path("maxItems")
                            .asInt()
            ).isEqualTo(3);
            assertThat(
                    schema.path("properties")
                            .path("answer")
                            .path("type")
                            .asString()
            ).isEqualTo("string");
        }
    }

    @Test
    @DisplayName("스트림은 빈 청크를 제외하고 첫 모델 콘텐츠 시각과 provider 사용량을 한 번 기록한다")
    void stream_success_logsFirstContentAndUsage() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":19,\"completion_tokens\":4}}\n\n" + "data: [DONE]\n\n"
            );
            try (LogCapture logs = new LogCapture(SearchLlmClient.class)) {
                client(
                        server,
                        http
                ).stream(
                        scope(),
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "private-question"
                                )
                        ),
                        text -> {
                        }
                );
                assertThat(logs.events()).filteredOn(
                        event -> event.getFormattedMessage()
                                .contains("phase=llm_first_content")
                )
                        .singleElement()
                        .satisfies(
                                event -> assertThat(event.getFormattedMessage()).contains(
                                        "operation=stream",
                                        "answerId=2",
                                        "outcome=received"
                                )
                                        .doesNotContain("private-question")
                        );
                assertThat(logs.events()).filteredOn(
                        event -> event.getFormattedMessage()
                                .contains("phase=llm_usage")
                )
                        .singleElement()
                        .satisfies(
                                event -> assertThat(event.getFormattedMessage()).contains(
                                        "promptTokens=19",
                                        "completionTokens=4"
                                )
                        );
            }
        }
    }

    @Test
    @DisplayName("DONE 없이 끊긴 스트림은 실패한다")
    void stream_failure_truncated() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"부분\"}}]}\n\n"
            );
            assertThatThrownBy(
                    () -> client(
                            server,
                            http
                    ).stream(
                            scope(),
                            List.of(),
                            text -> {
                            }
                    )
            ).hasMessage("INCOMPLETE_MODEL_STREAM");
        }
    }

    @Test
    @DisplayName("응답 헤더 뒤 본문이 멈춰도 전체 작업 기한 안에 연결을 종료한다")
    void stream_failure_bodyDeadline() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(exchange -> {
                exchange.sendResponseHeaders(
                        200,
                        0
                );
                exchange.getResponseBody()
                        .flush();
                try {
                    new CountDownLatch(1).await(
                            3,
                            TimeUnit.SECONDS
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                }
            });
            Instant now = Instant.now();
            SearchToolScope deadline = new SearchToolScope(
                    1,
                    1,
                    1,
                    1,
                    2,
                    UUID.randomUUID(),
                    now,
                    now.plusMillis(500)
            );
            long started = System.nanoTime();
            assertThatThrownBy(
                    () -> client(
                            server,
                            http
                    ).stream(
                            deadline,
                            List.of(),
                            text -> {
                            }
                    )
            ).isInstanceOf(SearchToolException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(2));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidStreamEvents")
    void stream_failure_providerErrors(
            String event,
            String code
    ) throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "data: " + event + "\n\ndata: [DONE]\n\n"
            );
            assertThatThrownBy(
                    () -> client(
                            server,
                            http
                    ).stream(
                            scope(),
                            List.of(),
                            text -> {
                            }
                    )
            ).isInstanceOf(SearchToolException.class)
                    .hasMessage(code);
        }
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidStreamEvents() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "{\"error\":{\"message\":\"provider failed\"}}",
                        "MODEL_GENERATION_FAILED"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "{\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}",
                        "INCOMPLETE_MODEL_STREAM"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "{\"choices\":[{\"finish_reason\":\"content_filter\",\"delta\":{}}]}",
                        "INCOMPLETE_MODEL_STREAM"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}",
                        "UNEXPECTED_MODEL_TOOL_CALL"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "{}",
                        "INVALID_MODEL_RESPONSE"
                )
        );
    }

    @Test
    void complete_failure_missingChoices() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "{}"
            );
            assertThatThrownBy(
                    () -> client(
                            server,
                            http
                    ).complete(
                            scope(),
                            List.of(),
                            List.of()
                    )
            ).isInstanceOf(SearchToolException.class)
                    .hasMessage("INVALID_MODEL_RESPONSE");
        }
    }

    @Test
    void evidence_success_requestsStrictJsonSchema() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer(); SearchAgentHttp http = transport()) {
            server.respond(
                    200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}"
            );
            client(
                    server,
                    http
            ).evidence(
                    scope(),
                    List.of()
            );
            var body = JsonMapper.builder()
                    .build()
                    .readTree(server.requestBody());
            var format = body.path("response_format");
            assertThat(
                    format.path("type")
                            .asString()
            ).isEqualTo("json_schema");
            assertThat(
                    format.path("json_schema")
                            .path("strict")
                            .asBoolean()
            ).isTrue();
            assertThat(
                    format.path("json_schema")
                            .path("schema")
                            .path("additionalProperties")
                            .asBoolean(true)
            ).isFalse();
            assertThat(
                    body.path("stream")
                            .asBoolean(true)
            ).isFalse();
        }
    }

    private SearchAgentHttp transport() {
        return new SearchAgentHttp(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                JsonMapper.builder()
                        .build(),
                Clock.systemUTC(),
                262144,
                cancellation
        );
    }

    private SearchLlmClient client(
            LlmHttpServer server,
            SearchAgentHttp http
    ) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(server.baseUrl());
        properties.setApiToken("test-token");
        properties.setModel("test-model");
        return new SearchLlmClient(
                http,
                properties
        );
    }

    private SearchToolScope scope() {
        Instant now = Instant.now();
        return new SearchToolScope(
                1,
                1,
                1,
                1,
                2,
                UUID.randomUUID(),
                now,
                now.plusSeconds(10)
        );
    }
}
