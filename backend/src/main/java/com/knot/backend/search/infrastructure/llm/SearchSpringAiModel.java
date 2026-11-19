package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.domain.SearchAttemptFailure;
import com.knot.backend.search.domain.SearchScopeExpiredException;
import com.knot.backend.search.domain.SearchToolException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

public final class SearchSpringAiModel {

    private final OpenAiChatModel model;
    private final SearchToolAccess access;
    private final Clock clock;
    private final Set<Long> workspaceIds;
    private final Duration pollInterval;
    private final long maxResponseBytes;

    SearchSpringAiModel(
            LlmProperties llm,
            SearchAgentProperties search,
            ObjectMapper mapper,
            SearchToolAccess access,
            Clock clock,
            List<Long> workspaceIds
    ) {
        llm.validateEnabledConfiguration();
        if (workspaceIds.isEmpty() || workspaceIds.stream()
                .anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Search generation requires explicit positive workspace IDs");
        }
        this.access = access;
        this.clock = clock;
        this.workspaceIds = Set.copyOf(workspaceIds);
        pollInterval = search.getCancellationPollInterval();
        maxResponseBytes = llm.getMaxResponseBytes();
        OpenAiChatModel.ResponseFormat format = OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(
                        mapper.valueToTree(SearchAnswerSchema.responseFormat())
                                .path("json_schema")
                                .path("schema")
                                .toString()
                )
                .strict(true)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(
                        llm.getBaseUrl()
                                .resolve("/v1")
                                .toString()
                )
                .apiKey(llm.getApiToken())
                .model(llm.getModel())
                .temperature(0.0)
                .timeout(llm.getRequestTimeout())
                .maxRetries(0)
                .responseFormat(format)
                .extraBody(
                        Map.of(
                                "chat_template_kwargs",
                                Map.of(
                                        "enable_thinking",
                                        false
                                ),
                                "reasoning",
                                Map.of(
                                        "enabled",
                                        false
                                )
                        )
                )
                .build();
        model = OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    public boolean supports(long workspaceId) {
        return workspaceIds.contains(workspaceId);
    }

    void stream(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            Consumer<String> delta
    ) {
        access.validate(scope);
        Duration remaining = Duration.between(
                clock.instant(),
                scope.expiresAt()
        );
        if (remaining.isNegative() || remaining.isZero()) {
            throw new SearchScopeExpiredException();
        }
        Prompt prompt = new Prompt(
                messages.stream()
                        .map(this::message)
                        .toList()
        );
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicLong bytes = new AtomicLong();
        Mono<Void> scopeMonitor = Flux.interval(pollInterval)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(tick -> access.validate(scope))
                .then();
        try {
            model.stream(prompt)
                    .takeUntilOther(scopeMonitor)
                    .doOnNext(response -> {
                        access.validate(scope);
                        response.getResults()
                                .forEach(result -> {
                                    inspect(
                                            result,
                                            stopped
                                    );
                                    String text = result.getOutput()
                                            .getText();
                                    emit(
                                            text,
                                            bytes,
                                            delta
                                    );
                                });
                    })
                    .blockLast(remaining);
        } catch (RuntimeException exception) {
            access.validate(scope);
            Throwable cause = exception;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof OpenAIServiceException serviceError) {
                throw new SearchToolException(SearchAttemptFailure.upstreamStatusCode(serviceError.statusCode()));
            }
            if (cause instanceof OpenAIIoException || cause instanceof IllegalStateException) {
                throw new SearchToolException(
                        "SEARCH_TRANSPORT_FAILED",
                        cause
                );
            }
            throw exception;
        }
        if (!stopped.get() || bytes.get() == 0) {
            throw new SearchToolException("INCOMPLETE_MODEL_STREAM");
        }
    }

    private Message message(Map<String, Object> message) {
        String text = (String) message.get("content");
        return switch (String.valueOf(message.get("role"))) {
            case "system" -> new SystemMessage(text);
            case "user" -> new UserMessage(text);
            case "assistant" -> new AssistantMessage(text);
            default -> throw new SearchToolException("INVALID_MODEL_RESPONSE");
        };
    }

    private void inspect(
            Generation generation,
            AtomicBoolean stopped
    ) {
        if (!generation.getOutput()
                .getToolCalls()
                .isEmpty()) {
            throw new SearchToolException("INVALID_MODEL_RESPONSE");
        }
        String finish = generation.getMetadata()
                .getFinishReason();
        if ("stop".equalsIgnoreCase(finish)) {
            stopped.set(true);
            return;
        }
        if (finish != null && !finish.isBlank()) {
            throw new SearchToolException("INCOMPLETE_MODEL_STREAM");
        }
    }

    private void emit(
            String text,
            AtomicLong bytes,
            Consumer<String> delta
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (bytes.addAndGet(text.getBytes(StandardCharsets.UTF_8).length) > maxResponseBytes) {
            throw new SearchToolException("INVALID_MODEL_RESPONSE");
        }
        delta.accept(text);
    }
}
