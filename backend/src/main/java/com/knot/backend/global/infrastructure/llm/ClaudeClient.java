package com.knot.backend.global.infrastructure.llm;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public class ClaudeClient implements LlmClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final LlmProperties properties;

    public ClaudeClient(
            HttpClient httpClient,
            ObjectMapper mapper,
            LlmProperties properties
    ) {
        this.httpClient = httpClient;
        this.mapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        this.properties = properties;
    }

    @Override
    public String complete(LlmCompletionRequest request) {
        new LlmRequestValidator().validate(request);
        ObjectNode body = createRequestBody(request);
        HttpResponse<String> response = awaitResponse(
                buildHttpRequest(
                        body,
                        effectiveTimeout(request)
                )
        );
        validateCompletionStatus(response);
        return readAssistantContent(response.body());
    }

    private ObjectNode createRequestBody(LlmCompletionRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put(
                "model",
                properties.getModel()
        );
        body.put(
                "max_tokens",
                request.options()
                        .maxTokens()
        );
        body.putObject("thinking")
                .put(
                        "type",
                        properties.getClaudeThinkingType()
                );
        ArrayNode messages = body.putArray("messages");
        ArrayNode system = body.putArray("system");
        for (LlmMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system.addObject()
                        .put(
                                "type",
                                "text"
                        )
                        .put(
                                "text",
                                message.content()
                        );
                continue;
            }
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) {
                throw new LlmException(LlmErrorCode.LLM_INVALID_REQUEST);
            }
            messages.addObject()
                    .put(
                            "role",
                            message.role()
                    )
                    .put(
                            "content",
                            message.content()
                    );
        }
        ObjectNode format = body.putObject("output_config")
                .putObject("format");
        format.put(
                "type",
                "json_schema"
        );
        JsonNode schema = request.outputSchema()
                .deepCopy();
        adaptSchema(schema);
        format.set(
                "schema",
                schema
        );
        return body;
    }

    // Claude's wire schema supports a subset. Domain response validators keep these
    // constraints.
    private void adaptSchema(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        for (String keyword : java.util.List.of(
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "multipleOf",
                "minLength",
                "maxLength",
                "minItems",
                "maxItems",
                "uniqueItems"
        )) {
            object.remove(keyword);
        }
        for (String keyword : java.util.List.of(
                "properties",
                "$defs",
                "definitions",
                "patternProperties"
        )) {
            for (JsonNode property : object.path(keyword)) {
                adaptSchema(property);
            }
        }
        for (String keyword : java.util.List.of(
                "items",
                "additionalProperties",
                "not"
        )) {
            adaptSchema(object.path(keyword));
        }
        for (String keyword : java.util.List.of(
                "anyOf",
                "oneOf",
                "allOf",
                "prefixItems"
        )) {
            for (JsonNode branch : object.path(keyword)) {
                adaptSchema(branch);
            }
        }
    }

    private Duration effectiveTimeout(LlmCompletionRequest request) {
        Duration configured = properties.getRequestTimeout();
        if (request.timeout() == null || request.timeout()
                .compareTo(configured) > 0) {
            return configured;
        }
        return request.timeout();
    }

    private HttpRequest buildHttpRequest(
            ObjectNode body,
            Duration timeout
    ) {
        return HttpRequest.newBuilder(
                properties.getBaseUrl()
                        .resolve("/v1/messages")
        )
                .timeout(timeout)
                .header(
                        "x-api-key",
                        properties.getApiToken()
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "anthropic-version",
                        "2023-06-01"
                )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body),
                                StandardCharsets.UTF_8
                        )
                )
                .build();
    }

    private HttpResponse<String> awaitResponse(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                        properties.getMaxResponseBytes()
                )
        );
        try {
            return future.get(
                    request.timeout()
                            .orElse(properties.getRequestTimeout())
                            .toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new LlmException(
                    LlmErrorCode.LLM_TIMEOUT,
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread()
                    .interrupt();
            throw new LlmException(
                    LlmErrorCode.LLM_CALL_INTERRUPTED,
                    exception
            );
        } catch (ExecutionException exception) {
            if (HttpTimeoutException.class.isInstance(exception.getCause())) {
                throw new LlmException(
                        LlmErrorCode.LLM_TIMEOUT,
                        exception
                );
            }
            throw new LlmException(
                    LlmErrorCode.LLM_UNAVAILABLE,
                    exception
            );
        }
    }

    private void validateCompletionStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new LlmException(LlmErrorCode.LLM_AUTHENTICATION_FAILED);
        }
        if (status == 429) {
            throw new LlmException(LlmErrorCode.LLM_RATE_LIMITED);
        }
        if (status >= 500) {
            throw new LlmException(LlmErrorCode.LLM_UNAVAILABLE);
        }
        if (status == 400 && isContextLimitResponse(response.body())) {
            throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
        }
        throw new LlmException(LlmErrorCode.LLM_REQUEST_REJECTED);
    }

    private boolean isContextLimitResponse(String body) {
        try {
            JsonNode error = mapper.readTree(body)
                    .path("error");
            String message = error.path("message")
                    .asString("");
            return "invalid_request_error".equals(
                    error.path("type")
                            .asString()
            ) && (message.startsWith("prompt is too long") || message.contains("exceeds the context window"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String readAssistantContent(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (JacksonException exception) {
            throw new LlmException(
                    LlmErrorCode.LLM_INVALID_RESPONSE,
                    exception
            );
        }
        if (root == null || !root.isObject()) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
        }
        String reason = root.path("stop_reason")
                .asString();
        if ("max_tokens".equals(reason)) {
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED);
        }
        if (!"end_turn".equals(reason) || !root.path("content")
                .isArray()) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(
                    block.path("type")
                            .asString()
            ) && block.path("text")
                    .isString()) {
                text.append(
                        block.path("text")
                                .asString()
                );
                continue;
            }
            if (!"thinking".equals(
                    block.path("type")
                            .asString()
            ) && !"redacted_thinking".equals(block.path("type")
                    .asString()
            )) {
                throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
            }
        }
        if (text.toString()
                .isBlank()) {
            throw new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE);
        }
        return text.toString();
    }
}
