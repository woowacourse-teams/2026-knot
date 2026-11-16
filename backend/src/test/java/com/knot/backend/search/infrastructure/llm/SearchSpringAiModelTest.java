package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.testsupport.LlmHttpServer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(10)
class SearchSpringAiModelTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final String PATH = "/v1/chat/completions";
    private final JsonMapper json = new JsonMapper();
    private final SearchToolScope scope = new SearchToolScope(
            1L,
            1L,
            1L,
            2L,
            3L,
            UUID.randomUUID(),
            NOW,
            NOW.plusSeconds(3)
    );

    @Test
    @DisplayName("Spring AI 스트리밍 요청에 기존 JSON 스키마를 포함하고 정상 종료한 본문을 조립한다")
    void stream_success_preservesSchemaAndFinishContract() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    chunk(
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    "첫 답변"
                            ),
                            null
                    ) + chunk(Map.of(),
                            "stop"
                    ) + "data: [DONE]\n\n"
            );
            SearchSpringAiModel model = model(
                    server,
                    mock(SearchToolAccess.class)
            );
            StringBuilder answer = new StringBuilder();

            model.stream(
                    scope,
                    messages(),
                    answer::append
            );

            assertThat(answer).hasToString("첫 답변");
            assertThat(
                    json.readTree(server.requestBody())
                            .at("/response_format/json_schema/schema/properties/evidenceIds/maxItems")
                            .asInt()
            ).isEqualTo(3);
            assertThat(model.supports(scope.workspaceId())).isTrue();
            assertThat(model.supports(458L)).isFalse();
        }
    }

    @Test
    @DisplayName("Spring AI 공급자가 길이 초과로 끝내면 완성된 모델 응답으로 처리하지 않는다")
    void stream_failure_rejectsTruncatedCompletion() throws Exception {
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    chunk(
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    "초안"
                            ),
                            "length"
                    ) + "data: [DONE]\n\n"
            );

            assertThatThrownBy(
                    () -> model(
                            server,
                            mock(SearchToolAccess.class)
                    ).stream(
                            scope,
                            messages(),
                            text -> {
                            }
                    )
            ).isInstanceOf(SearchToolException.class)
                    .hasMessage("INCOMPLETE_MODEL_STREAM");
        }
    }

    @Test
    @DisplayName("공급자가 첫 내용을 보내지 않아도 권한이 사라지면 연결을 중단한다")
    void stream_failure_cancelsIdleRequestAfterAccessRevocation() throws Exception {
        SearchToolAccess access = mock(SearchToolAccess.class);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean revoked = new AtomicBoolean();
        doAnswer(invocation -> {
            if (revoked.get()) {
                throw new SearchToolException("SEARCH_ACCESS_DENIED");
            }
            return null;
        }).when(access)
                .validate(scope);
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    PATH,
                    exchange -> {
                        exchange.getResponseHeaders()
                                .add(
                                        "Content-Type",
                                        "text/event-stream"
                                );
                        exchange.sendResponseHeaders(
                                200,
                                0
                        );
                        revoked.set(true);
                        try {
                            release.await(
                                    5,
                                    TimeUnit.SECONDS
                            );
                        } catch (InterruptedException exception) {
                            Thread.currentThread()
                                    .interrupt();
                        }
                    }
            );
            try {
                assertThatThrownBy(
                        () -> model(
                                server,
                                access
                        ).stream(
                                scope,
                                messages(),
                                text -> {
                                }
                        )
                ).isInstanceOf(SearchToolException.class)
                        .hasMessage("SEARCH_ACCESS_DENIED");
            } finally {
                release.countDown();
            }
        }
    }

    private SearchSpringAiModel model(
            LlmHttpServer server,
            SearchToolAccess access
    ) {
        LlmProperties llm = new LlmProperties();
        llm.setEnabled(true);
        llm.setBaseUrl(server.baseUrl());
        llm.setApiToken("test-token");
        llm.setModel("test-model");
        SearchAgentProperties search = new SearchAgentProperties();
        search.setCancellationPollInterval(Duration.ofMillis(30));
        return new SearchSpringAiModel(
                llm,
                search,
                json,
                access,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                List.of(1L)
        );
    }

    private List<Map<String, Object>> messages() {
        return List.of(
                Map.of(
                        "role",
                        "user",
                        "content",
                        "어떤 결정이 있었어?"
                )
        );
    }

    private String chunk(
            Map<String, String> delta,
            String reason
    ) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put(
                "index",
                0
        );
        choice.put(
                "delta",
                delta
        );
        choice.put(
                "finish_reason",
                reason
        );
        return "data: " + json.writeValueAsString(
                Map.of(
                        "id",
                        "test",
                        "object",
                        "chat.completion.chunk",
                        "created",
                        1,
                        "model",
                        "test-model",
                        "choices",
                        List.of(choice)
                )
        ) + "\n\n";
    }

    private void respond(
            LlmHttpServer server,
            String events
    ) {
        server.respond(
                PATH,
                exchange -> {
                    byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .add(
                                    "Content-Type",
                                    "text/event-stream"
                            );
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
