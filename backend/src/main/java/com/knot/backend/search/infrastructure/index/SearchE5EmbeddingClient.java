package com.knot.backend.search.infrastructure.index;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class SearchE5EmbeddingClient {
    public static final int DIMENSIONS = 384;
    private static final int BATCH_SIZE = 24;
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "[::1]"
    );

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;
    private final String token;
    private final Duration timeout;

    public SearchE5EmbeddingClient(
            HttpClient http,
            ObjectMapper mapper,
            URI endpoint,
            String model,
            String token,
            Duration timeout
    ) {
        validateEndpoint(endpoint);
        this.http = http;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.model = model;
        this.token = token;
        this.timeout = timeout;
    }

    private void validateEndpoint(URI endpoint) {
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("Invalid E5 embedding endpoint");
        }
        if ("https".equals(endpoint.getScheme())) {
            return;
        }
        if ("http".equals(endpoint.getScheme()) && LOOPBACK_HOSTS.contains(endpoint.getHost())) {
            return;
        }
        throw new IllegalArgumentException("E5 embedding endpoint must use HTTPS or loopback HTTP");
    }

    public float[] embedQuery(String question) {
        return embed(List.of("query: " + question)).getFirst();
    }

    public List<float[]> embedPassages(List<String> passages) {
        List<float[]> vectors = new ArrayList<>(passages.size());
        for (int start = 0; start < passages.size(); start += BATCH_SIZE) {
            int end = Math.min(
                    start + BATCH_SIZE,
                    passages.size()
            );
            List<String> inputs = passages.subList(
                    start,
                    end
            )
                    .stream()
                    .map(passage -> "passage: " + passage)
                    .toList();
            vectors.addAll(embed(inputs));
        }
        return List.copyOf(vectors);
    }

    private List<float[]> embed(List<String> inputs) {
        try {
            HttpResponse<String> response = http.send(
                    request(inputs),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException("E5 embedding endpoint returned HTTP " + response.statusCode());
            }
            return orderedVectors(
                    mapper.readTree(response.body())
                            .path("data"),
                    inputs.size()
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "E5 embedding request failed",
                    exception
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid E5 embedding response");
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "E5 embedding request interrupted",
                    exception
            );
        }
    }

    private HttpRequest request(List<String> inputs) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "model",
                                                model,
                                                "input",
                                                inputs,
                                                "encoding_format",
                                                "float"
                                        )
                                )
                        )
                );
        if (!token.isBlank()) {
            request.header(
                    "Authorization",
                    "Bearer " + token
            );
        }
        return request.build();
    }

    private List<float[]> orderedVectors(
            JsonNode data,
            int size
    ) {
        if (!data.isArray() || data.size() != size) {
            throw new IllegalStateException("Invalid E5 embedding count");
        }
        float[][] ordered = new float[size][];
        for (JsonNode item : data) {
            JsonNode index = item.path("index");
            int position = index.asInt(-1);
            if (!index.isIntegralNumber() || position < 0 || position >= size || ordered[position] != null) {
                throw new IllegalStateException("Invalid E5 embedding index");
            }
            ordered[position] = vector(item.path("embedding"));
        }
        return List.copyOf(Arrays.asList(ordered));
    }

    private float[] vector(JsonNode values) {
        if (!values.isArray() || values.size() != DIMENSIONS) {
            throw new IllegalStateException("Invalid E5 embedding dimensions");
        }
        float[] vector = new float[DIMENSIONS];
        double squaredNorm = 0;
        for (int index = 0; index < DIMENSIONS; index++) {
            JsonNode value = values.get(index);
            double number = value.asDouble(Double.NaN);
            if (!value.isNumber() || !Float.isFinite((float) number)) {
                throw new IllegalStateException("Invalid E5 embedding value");
            }
            vector[index] = (float) number;
            squaredNorm += (double) vector[index] * vector[index];
        }
        if (squaredNorm == 0) {
            throw new IllegalStateException("Invalid zero E5 embedding");
        }
        double norm = Math.sqrt(squaredNorm);
        for (int index = 0; index < DIMENSIONS; index++) {
            vector[index] = (float) (vector[index] / norm);
        }
        return vector;
    }
}
