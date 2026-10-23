package com.knot.backend.global.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.LlmHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LmStudioClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("요청별 남은 배정 예산을 전체 응답 대기 시간에도 적용한다")
    void complete_failure_perRequestTimeout() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(exchange -> {
                exchange.sendResponseHeaders(
                        200,
                        0
                );
                exchange.getResponseBody()
                        .write('{');
                exchange.getResponseBody()
                        .flush();
                try {
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                }
            });
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(5),
                    4096
            );
            LlmCompletionRequest baseline = request();
            LlmCompletionRequest bounded = new LlmCompletionRequest(
                    baseline.messages(),
                    baseline.schemaName(),
                    baseline.outputSchema(),
                    baseline.options(),
                    Duration.ofMillis(500)
            );
            long startedAt = System.nanoTime();

            // when & then
            assertThatThrownBy(() -> client.complete(bounded)).hasMessage(LlmErrorCode.LLM_TIMEOUT.getMessage());
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("전체 원문과 JSON 스키마 및 분류 옵션으로 한 번 호출한다")
    void complete_success() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    """
                            {"choices":[{"finish_reason":"stop","message":{"content":"{\\\"topics\\\":[\\\"검색\\\"]}"}}]}
                            """
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );
            LlmCompletionRequest request = request();

            // when
            String content = client.complete(request);

            // then
            assertThat(content).isEqualTo("{\"topics\":[\"검색\"]}");
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.authorization()).isEqualTo("Bearer test-secret");
            JsonNode body = mapper.readTree(server.requestBody());
            assertThat(
                    body.path("model")
                            .asString()
            ).isEqualTo("test-model");
            assertThat(
                    body.path("stream")
                            .asBoolean()
            ).isFalse();
            assertThat(
                    body.path("messages")
                            .get(1)
                            .path("content")
                            .asString()
            ).isEqualTo("전체 원문");
            assertThat(
                    body.path("temperature")
                            .asDouble()
            ).isEqualTo(0.2);
            assertThat(
                    body.path("max_tokens")
                            .asInt()
            ).isEqualTo(1024);
            assertThat(
                    body.path("reasoning")
                            .asString()
            ).isEqualTo("off");
            assertThat(
                    body.path("chat_template_kwargs")
                            .path("enable_thinking")
                            .asBoolean()
            ).isFalse();
            assertThat(
                    body.path("response_format")
                            .path("json_schema")
                            .path("strict")
                            .asBoolean()
            ).isTrue();
            assertThat(
                    request.outputSchema()
                            .has("name")
            ).isFalse();
        }
    }

    @Test
    @DisplayName("추론 예산은 실제 HTTP 실험에서 사용한 필드로 전달한다")
    void complete_success_thinkingBudget() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    """
                            {"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}
                            """
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );
            LlmCompletionRequest baseline = request();
            LlmCompletionRequest request = new LlmCompletionRequest(
                    baseline.messages(),
                    baseline.schemaName(),
                    baseline.outputSchema(),
                    new LlmGenerationOptions(
                            0.2,
                            0.9,
                            20,
                            1.0,
                            true,
                            512,
                            8192
                    )
            );

            // when
            client.complete(request);

            // then
            JsonNode body = mapper.readTree(server.requestBody());
            assertThat(body.has("thinking_budget_tokens")).isTrue();
            assertThat(
                    body.path("thinking_budget_tokens")
                            .asInt()
            ).isEqualTo(512);
            assertThat(body.has("reasoning_budget")).isFalse();
            assertThat(
                    body.path("reasoning")
                            .asString()
            ).isEqualTo("on");
            JsonNode templateOptions = body.path("chat_template_kwargs");
            assertThat(
                    templateOptions.path("enable_thinking")
                            .asBoolean()
            ).isTrue();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("잘못된 호출 입력은 외부 요청 전에 거절한다")
    void complete_failure_invalidRequest() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(null)).hasMessage("LLM 요청 정보가 올바르지 않습니다");
            assertThat(server.requestCount()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"401, LLM_AUTHENTICATION_FAILED", "403, LLM_AUTHENTICATION_FAILED", "429, LLM_RATE_LIMITED",
            "503, LLM_UNAVAILABLE", "400, LLM_REQUEST_REJECTED", "302, LLM_REQUEST_REJECTED"})
    @DisplayName("공급자 HTTP 오류를 구분하고 자동 재시도하지 않는다")
    void complete_failure_providerStatus(
            int status,
            LlmErrorCode expected
    ) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    status,
                    "민감한 원문과 공급자 오류"
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request())).isInstanceOf(LlmException.class)
                    .satisfies(exception -> assertThat(((LlmException) exception).getErrorCode()).isEqualTo(expected))
                    .hasMessage(expected.getMessage())
                    .hasNoCause();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[]", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":42}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\" \"}}]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]} {}",
            "{\"choices\":[],\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}"})
    @DisplayName("잘못된 완료 응답을 성공으로 처리하지 않는다")
    void complete_failure_invalidResponse(String body) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    body
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request())).isInstanceOf(LlmException.class)
                    .hasMessage(LlmErrorCode.LLM_INVALID_RESPONSE.getMessage());
        }
    }

    @Test
    @DisplayName("출력 토큰 한도로 잘린 결과를 정상 JSON으로 사용하지 않는다")
    void complete_failure_outputLimit() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    """
                            {"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}
                            """
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request()))
                    .hasMessage(LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED.getMessage());
        }
    }

    @Test
    @DisplayName("헤더 이후 본문이 멈춰도 전체 응답 기한을 적용한다")
    void complete_failure_stalledBodyTimeout() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(exchange -> {
                exchange.sendResponseHeaders(
                        200,
                        0
                );
                exchange.getResponseBody()
                        .write('{');
                exchange.getResponseBody()
                        .flush();
                try {
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                }
            });
            LmStudioClient client = client(
                    server,
                    Duration.ofMillis(500),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request())).hasMessage(LlmErrorCode.LLM_TIMEOUT.getMessage())
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(server.requestCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("응답 본문 byte 한도를 초과하면 전체 내용을 받지 않는다")
    void complete_failure_responseByteLimit() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    "x".repeat(4096)
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    32
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request())).hasMessage(LlmErrorCode.LLM_UNAVAILABLE.getMessage());
        }
    }

    @Test
    @DisplayName("관찰한 LM Studio 컨텍스트 초과 오류를 입력 한도 실패로 전달한다")
    void complete_failure_inputLimit() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            ObjectNode embeddedError = mapper.createObjectNode();
            embeddedError.putObject("error")
                    .put(
                            "type",
                            "exceed_context_size_error"
                    );
            String embedded = mapper.writeValueAsString(embeddedError);
            server.respond(
                    400,
                    mapper.writeValueAsString(
                            mapper.createObjectNode()
                                    .put(
                                            "error",
                                            "Engine protocol predict request returned 400: " + embedded
                                    )
                    )
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );

            // when & then
            assertThatThrownBy(() -> client.complete(request()))
                    .hasMessage(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED.getMessage());
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("호출 대기 중단은 interrupt 표시를 유지하며 실패를 반환한다")
    void complete_failure_interrupted() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    200,
                    "{}"
            );
            LmStudioClient client = client(
                    server,
                    Duration.ofSeconds(2),
                    4096
            );
            Thread.currentThread()
                    .interrupt();

            // when & then
            assertThatThrownBy(() -> client.complete(request()))
                    .hasMessage(LlmErrorCode.LLM_CALL_INTERRUPTED.getMessage());
            assertThat(
                    Thread.currentThread()
                            .isInterrupted()
            ).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private LmStudioClient client(
            LlmHttpServer server,
            Duration timeout,
            long maxBytes
    ) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(server.baseUrl());
        properties.setApiToken("test-secret");
        properties.setModel("test-model");
        properties.setRequestTimeout(timeout);
        properties.setMaxResponseBytes(maxBytes);
        return new LmStudioClient(
                HttpClient.newHttpClient(),
                mapper,
                properties
        );
    }

    private LlmCompletionRequest request() {
        return new LlmCompletionRequest(
                List.of(
                        new LlmMessage(
                                "system",
                                "분류 규칙"
                        ),
                        new LlmMessage(
                                "user",
                                "전체 원문"
                        )
                ),
                "topics",
                mapper.readTree("{\"type\":\"object\"}"),
                new LlmGenerationOptions(
                        0.2,
                        0.9,
                        20,
                        1.0,
                        false,
                        null,
                        1024
                )
        );
    }
}
