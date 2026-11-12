package com.knot.backend.global.infrastructure.llm;
import static org.assertj.core.api.Assertions.*;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.LlmHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
class ClaudeClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ClaudeClient client(LlmHttpServer server) {
        LlmProperties p = new LlmProperties();
        p.setEnabled(true);
        p.setProvider("claude");
        p.setBaseUrl(server.baseUrl());
        p.setApiToken("test-secret");
        p.setModel("claude-sonnet-5-5");
        p.setRequestTimeout(Duration.ofSeconds(3));
        return new ClaudeClient(
                HttpClient.newHttpClient(),
                mapper,
                p
        );
    }
    private LlmCompletionRequest request() {
        return new LlmCompletionRequest(
                List.of(
                        new LlmMessage(
                                "system",
                                "지시"
                        ),
                        new LlmMessage(
                                "user",
                                "원문"
                        )
                ),
                "summary",
                mapper.readTree(
                        "{\"type\":\"object\",\"properties\":{\"minimum\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"minimum\"],\"additionalProperties\":false}"
                ),
                new LlmGenerationOptions(
                        0.2,
                        1,
                        0,
                        1,
                        false,
                        null,
                        1024
                )
        );
    }
    @Test
    void translatesSchemaAndMessagesWithoutDroppingPropertyNamedMinimum() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    "/v1/messages",
                    exchange -> {
                        assertThat(
                                exchange.getRequestHeaders()
                                        .getFirst("x-api-key")
                        ).isEqualTo("test-secret");
                        assertThat(
                                exchange.getRequestHeaders()
                                        .getFirst("anthropic-version")
                        ).isEqualTo("2023-06-01");
                        byte[] bytes = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private\"},{\"type\":\"text\",\"text\":\"{}\"}]}"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(
                                200,
                                bytes.length
                        );
                        exchange.getResponseBody()
                                .write(bytes);
                    }
            );
            assertThat(client(server).complete(request())).isEqualTo("{}");
            var body = mapper.readTree(server.requestBody());
            assertThat(
                    body.path("system")
                            .get(0)
                            .path("text")
                            .asString()
            ).isEqualTo("지시");
            assertThat(
                    body.path("messages")
                            .size()
            ).isEqualTo(1);
            assertThat(
                    body.path("output_config")
                            .path("format")
                            .path("schema")
                            .path("properties")
                            .has("minimum")
            ).isTrue();
            assertThat(
                    body.path("output_config")
                            .path("format")
                            .path("schema")
                            .path("properties")
                            .path("minimum")
                            .has("minLength")
            ).isFalse();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}",
            "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}",
            "{\"stop_reason\":\"end_turn\",\"content\":[]}"})
    void rejectsIncompleteResponses(String response) throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    "/v1/messages",
                    exchange -> {
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(
                                200,
                                bytes.length
                        );
                        exchange.getResponseBody()
                                .write(bytes);
                    }
            );
            assertThatThrownBy(() -> client(server).complete(request())).isInstanceOf(LlmException.class);
        }
    }
}
