package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.testsupport.LlmHttpServer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchE5EmbeddingClientTest {

    @Test
    @DisplayName("E5 문서 접두어를 붙여 배치 요청하고 인덱스 순서대로 정규화된 벡터를 받는다")
    void embedPassages_success_ordersAndNormalizesVectors() throws Exception {
        float[] first = new float[SearchE5EmbeddingClient.DIMENSIONS];
        first[0] = 3;
        first[1] = 4;
        float[] second = new float[SearchE5EmbeddingClient.DIMENSIONS];
        second[0] = -2;
        JsonMapper json = new JsonMapper();
        try (LlmHttpServer server = new LlmHttpServer(); HttpClient http = HttpClient.newHttpClient()) {
            reply(
                    server,
                    json.writeValueAsString(
                            Map.of(
                                    "data",
                                    List.of(
                                            Map.of(
                                                    "index",
                                                    1,
                                                    "embedding",
                                                    second
                                            ),
                                            Map.of(
                                                    "index",
                                                    0,
                                                    "embedding",
                                                    first
                                            )
                                    )
                            )
                    )
            );
            SearchE5EmbeddingClient client = client(
                    server,
                    http,
                    json
            );

            List<float[]> vectors = client.embedPassages(
                    List.of(
                            "배포 결정",
                            "고객 인터뷰"
                    )
            );

            assertThat(
                    json.readTree(server.requestBody())
                            .path("input")
                            .toString()
            ).isEqualTo("[\"passage: 배포 결정\",\"passage: 고객 인터뷰\"]");
            assertThat(vectors.getFirst()).startsWith(
                    0.6f,
                    0.8f
            );
            assertThat(vectors.getLast()).startsWith(-1.0f);
        }
    }

    @Test
    @DisplayName("E5 질문 접두어를 사용하며 잘못된 차원은 색인에 전달하지 않는다")
    void embedQuery_failure_invalidDimensions() throws Exception {
        JsonMapper json = new JsonMapper();
        try (LlmHttpServer server = new LlmHttpServer(); HttpClient http = HttpClient.newHttpClient()) {
            reply(
                    server,
                    "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}"
            );
            SearchE5EmbeddingClient client = client(
                    server,
                    http,
                    json
            );

            assertThatThrownBy(() -> client.embedQuery("언제 배포해?")).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid E5 embedding dimensions");
            assertThat(
                    json.readTree(server.requestBody())
                            .path("input")
                            .get(0)
                            .asString()
            ).isEqualTo("query: 언제 배포해?");
        }
    }

    private SearchE5EmbeddingClient client(
            LlmHttpServer server,
            HttpClient http,
            JsonMapper json
    ) {
        return new SearchE5EmbeddingClient(
                http,
                json,
                server.baseUrl()
                        .resolve("/v1/embeddings"),
                "multilingual-e5-small",
                "",
                Duration.ofSeconds(3)
        );
    }

    private void reply(
            LlmHttpServer server,
            String body
    ) {
        server.respond(
                "/v1/embeddings",
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length
                    );
                    exchange.getResponseBody()
                            .write(bytes);
                }
        );
    }
}
