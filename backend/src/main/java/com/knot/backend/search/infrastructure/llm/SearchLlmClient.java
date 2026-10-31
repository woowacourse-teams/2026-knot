package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.SearchToolException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RequiredArgsConstructor
@Slf4j
public class SearchLlmClient {

    private final SearchAgentHttp http;
    private final LlmProperties properties;

    public JsonNode intent(
            SearchToolScope scope,
            List<Map<String, Object>> messages
    ) {
        Map<String, Object> request = body(
                messages,
                false
        );
        request.put(
                "max_tokens",
                128
        );
        request.put(
                "reasoning_effort",
                "none"
        );
        request.put(
                "response_format",
                intentFormat()
        );
        JsonNode response = requestJson(
                scope,
                "intent",
                messages,
                request,
                Map.of()
        );
        JsonNode choice = firstChoice(response);
        validateIntentChoice(
                response,
                choice
        );
        JsonNode result = message(response);
        validateIntentMessage(result);
        return result;
    }

    private Map<String, Object> intentFormat() {
        List<String> labels = Arrays.stream(SearchIntent.values())
                .map(Enum::name)
                .toList();
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "intent",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                labels
                        )
                ),
                "required",
                List.of("intent"),
                "additionalProperties",
                false
        );
        return Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of(
                        "name",
                        "intent",
                        "strict",
                        true,
                        "schema",
                        schema
                )
        );
    }

    private void validateIntentChoice(
            JsonNode response,
            JsonNode choice
    ) {
        if (response.has("error") || response.path("choices")
                .size() != 1) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
        if (!"stop".equals(
                choice.path("finish_reason")
                        .asString()
        )) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
    }

    private void validateIntentMessage(JsonNode message) {
        if (!"assistant".equals(
                message.path("role")
                        .asString()
        )) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
        if (!message.path("content")
                .isString()) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
        if (message.hasNonNull("refusal")) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
        validateIntentToolCalls(message.path("tool_calls"));
    }

    private void validateIntentToolCalls(JsonNode calls) {
        if (calls.isMissingNode() || calls.isNull()) {
            return;
        }
        if (!calls.isArray() || !calls.isEmpty()) {
            throw new SearchToolException("INVALID_INTENT_RESPONSE");
        }
    }

    public JsonNode complete(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools
    ) {
        Map<String, Object> body = body(
                messages,
                false
        );
        if (!tools.isEmpty()) {
            body.put(
                    "tools",
                    tools
            );
            body.put(
                    "tool_choice",
                    "auto"
            );
        }
        return message(
                requestJson(
                        scope,
                        "complete",
                        messages,
                        body,
                        Map.of()
                )
        );
    }

    public JsonNode evidence(
            SearchToolScope scope,
            List<Map<String, Object>> messages
    ) {
        Map<String, Object> body = body(
                messages,
                false
        );
        body.put(
                "response_format",
                Map.of(
                        "type",
                        "json_schema",
                        "json_schema",
                        Map.of(
                                "name",
                                "evidence",
                                "strict",
                                true,
                                "schema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "usedDocumentIds",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map.of(
                                                                "type",
                                                                "integer"
                                                        )
                                                )
                                        ),
                                        "required",
                                        List.of("usedDocumentIds"),
                                        "additionalProperties",
                                        false
                                )
                        )
                )
        );
        return message(
                requestJson(
                        scope,
                        "evidence",
                        messages,
                        body,
                        Map.of()
                )
        );
    }

    public void stream(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta
    ) {
        long started = System.nanoTime();
        long[] firstContentAt = {-1};
        String[] promptTokens = {"unavailable"};
        String[] completionTokens = {"unavailable"};
        AtomicBoolean usageLogged = new AtomicBoolean();
        try {
            Map<String, Object> request = body(
                    messages,
                    true
            );
            request.put(
                    "response_format",
                    SearchAnswerSchema.responseFormat()
            );
            http.stream(
                    endpoint(),
                    properties.getApiToken(),
                    request,
                    scope,
                    event -> {
                        if (event.has("error")) {
                            throw new SearchToolException("MODEL_GENERATION_FAILED");
                        }
                        JsonNode choices = event.path("choices");
                        JsonNode usage = event.path("usage");
                        if (usage.isObject()) {
                            captureUsage(
                                    scope,
                                    "stream",
                                    usage,
                                    usageLogged,
                                    promptTokens,
                                    completionTokens
                            );
                        }
                        if (isUsageOnly(
                                choices,
                                usage
                        )) {
                            return;
                        }
                        JsonNode choice = firstChoice(event);
                        String finish = choice.path("finish_reason")
                                .asString("");
                        if ("length".equals(finish) || "content_filter".equals(finish)) {
                            throw new SearchToolException("INCOMPLETE_MODEL_STREAM");
                        }
                        JsonNode delta = choice.path("delta");
                        if (delta.has("tool_calls")) {
                            throw new SearchToolException("UNEXPECTED_MODEL_TOOL_CALL");
                        }
                        String text = delta.path("content")
                                .asString("");
                        if (!text.isEmpty()) {
                            if (firstContentAt[0] < 0) {
                                firstContentAt[0] = System.nanoTime();
                                log.info(
                                        "Search latency phase=llm_first_content operation=stream workspaceId={} answerId={} elapsedMs={} messageContentChars={} outcome=received",
                                        scope.workspaceId(),
                                        scope.answerId(),
                                        (firstContentAt[0] - started) / 1_000_000,
                                        inputChars(messages)
                                );
                            }
                            onDelta.accept(text);
                        }
                    }
            );
            log.info(
                    "Search latency phase=llm_call operation=stream workspaceId={} answerId={} elapsedMs={} messageContentChars={} firstContentMs={} promptTokens={} completionTokens={} outcome=success",
                    scope.workspaceId(),
                    scope.answerId(),
                    (System.nanoTime() - started) / 1_000_000,
                    inputChars(messages),
                    firstContentMs(
                            firstContentAt[0],
                            started
                    ),
                    promptTokens[0],
                    completionTokens[0]
            );
        } catch (RuntimeException exception) {
            log.info(
                    "Search latency phase=llm_call operation=stream workspaceId={} answerId={} elapsedMs={} messageContentChars={} firstContentMs={} promptTokens={} completionTokens={} outcome=failure",
                    scope.workspaceId(),
                    scope.answerId(),
                    (System.nanoTime() - started) / 1_000_000,
                    inputChars(messages),
                    firstContentMs(
                            firstContentAt[0],
                            started
                    ),
                    promptTokens[0],
                    completionTokens[0]
            );
            throw exception;
        }
    }

    private JsonNode requestJson(
            SearchToolScope scope,
            String operation,
            List<Map<String, Object>> messages,
            Object request,
            Map<String, String> headers
    ) {
        long started = System.nanoTime();
        try {
            JsonNode response = http.json(
                    endpoint(),
                    properties.getApiToken(),
                    request,
                    scope,
                    headers
            );
            JsonNode usage = response.path("usage");
            log.info(
                    "Search latency phase=llm_call operation={} workspaceId={} answerId={} elapsedMs={} messageContentChars={} promptTokens={} completionTokens={} outcome=success",
                    operation,
                    scope.workspaceId(),
                    scope.answerId(),
                    (System.nanoTime() - started) / 1_000_000,
                    inputChars(messages),
                    tokenCount(
                            usage,
                            "prompt_tokens"
                    ),
                    tokenCount(
                            usage,
                            "completion_tokens"
                    )
            );
            return response;
        } catch (RuntimeException exception) {
            log.info(
                    "Search latency phase=llm_call operation={} workspaceId={} answerId={} elapsedMs={} messageContentChars={} promptTokens=unavailable completionTokens=unavailable outcome=failure",
                    operation,
                    scope.workspaceId(),
                    scope.answerId(),
                    (System.nanoTime() - started) / 1_000_000,
                    inputChars(messages)
            );
            throw exception;
        }
    }

    private void captureUsage(
            SearchToolScope scope,
            String operation,
            JsonNode usage,
            AtomicBoolean usageLogged,
            String[] promptTokens,
            String[] completionTokens
    ) {
        if (!usageLogged.compareAndSet(
                false,
                true
        )) {
            return;
        }
        promptTokens[0] = tokenCount(
                usage,
                "prompt_tokens"
        );
        completionTokens[0] = tokenCount(
                usage,
                "completion_tokens"
        );
        log.info(
                "Search latency phase=llm_usage operation={} workspaceId={} answerId={} promptTokens={} completionTokens={} outcome=success",
                operation,
                scope.workspaceId(),
                scope.answerId(),
                promptTokens[0],
                completionTokens[0]
        );
    }

    private String tokenCount(
            JsonNode usage,
            String field
    ) {
        JsonNode value = usage.path(field);
        if (!value.isNumber()) {
            return "unavailable";
        }
        return Long.toString(value.asLong());
    }

    private int inputChars(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content instanceof String text) {
                total += text.length();
            }
        }
        return total;
    }

    private boolean isUsageOnly(
            JsonNode choices,
            JsonNode usage
    ) {
        return (!choices.isArray() || choices.isEmpty()) && usage.isObject();
    }

    private String firstContentMs(
            long firstContentAt,
            long started
    ) {
        if (firstContentAt < 0) {
            return "unavailable";
        }
        return Long.toString((firstContentAt - started) / 1_000_000);
    }

    private Map<String, Object> body(
            List<Map<String, Object>> messages,
            boolean stream
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "model",
                properties.getModel()
        );
        result.put(
                "messages",
                messages
        );
        result.put(
                "stream",
                stream
        );
        result.put(
                "temperature",
                0
        );
        result.put(
                "chat_template_kwargs",
                Map.of(
                        "enable_thinking",
                        false
                )
        );
        result.put(
                "reasoning",
                Map.of(
                        "enabled",
                        false
                )
        );
        return result;
    }

    private JsonNode message(JsonNode response) {
        JsonNode message = firstChoice(response).path("message");
        if (!message.isObject()) {
            throw new SearchToolException("INVALID_MODEL_RESPONSE");
        }
        return message;
    }

    private JsonNode firstChoice(JsonNode response) {
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new SearchToolException("INVALID_MODEL_RESPONSE");
        }
        return choices.get(0);
    }

    private URI endpoint() {
        return properties.getBaseUrl()
                .resolve("/v1/chat/completions");
    }
}
