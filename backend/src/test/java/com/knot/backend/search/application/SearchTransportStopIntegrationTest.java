package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import static org.mockito.Mockito.when;
import com.knot.backend.search.domain.model.SearchIntent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.infrastructure.llm.SearchAgentHttp;
import com.knot.backend.search.infrastructure.llm.SearchTransportCancellation;
import com.knot.backend.search.infrastructure.mcp.SearchMcpClient;
import com.knot.backend.search.infrastructure.mcp.SearchToolTokenCodec;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import com.knot.backend.search.infrastructure.llm.SearchLlmClient;
import com.knot.backend.search.infrastructure.llm.SearchQwenIntentClassifier;
import com.knot.backend.testsupport.LlmHttpServer;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(30)
class SearchTransportStopIntegrationTest {

    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchToolAccess access;
    @Autowired
    private SearchHistoryQuery history;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchAnswerGenerator generator;
    private long workspaceId;
    private long memberId;
    private SearchQuestionAcceptedResult accepted;

    @BeforeEach
    void setUp() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchUsePlan.of(SearchIntent.MEETING_SEARCH));
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("transport 중지");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEADERS", "JSON", "SSE", "NOTIFICATION", "MCP"})
    @DisplayName("공급자가 헤더·JSON·SSE·MCP 응답을 멈춰도 다른 DB 트랜잭션의 중지를 감지한다")
    void exchange_success_stopsSilentProvider(String mode) throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SearchToolScope scope = scope();
        try (LlmHttpServer server = new LlmHttpServer();
                SearchTransportCancellation cancellation = new SearchTransportCancellation(
                        access,
                        Duration.ofMillis(20)
                );
                SearchAgentHttp http = new SearchAgentHttp(
                        HttpClient.newHttpClient(),
                        JsonMapper.builder()
                                .build(),
                        Clock.systemUTC(),
                        262144,
                        cancellation
                );
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.respond(
                    providerPath(mode),
                    exchange -> {
                        if (mode.equals("JSON") || mode.equals("SSE")) {
                            exchange.sendResponseHeaders(
                                    200,
                                    0
                            );
                            exchange.getResponseBody()
                                    .flush();
                        }
                        requested.countDown();
                        try {
                            release.await(
                                    10,
                                    TimeUnit.SECONDS
                            );
                        } catch (InterruptedException exception) {
                            Thread.currentThread()
                                    .interrupt();
                        }
                    }
            );
            Future<?> running = executor.submit(() -> {
                assertThatThrownBy(
                        () -> call(
                                http,
                                server,
                                scope,
                                mode
                        )
                ).isInstanceOf(SearchToolException.class);
            });
            assertThat(
                    requested.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            long started = System.nanoTime();
            executions.stop(
                    workspaceId,
                    memberId,
                    accepted.conversationId(),
                    accepted.answerMessageId()
            );
            running.get(
                    3,
                    TimeUnit.SECONDS
            );
            if (mode.equals("MCP")) {
                assertThat(server.requestBody()).contains(
                        "initialize",
                        "jsonrpc"
                );
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(
                    jdbc.sql("SELECT status FROM search_messages WHERE role='ASSISTANT'")
                            .query(String.class)
                            .single()
            ).isEqualTo("STOPPED");
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("분류·검색 HTTP 대기 중 중지는 worker를 반환시키고 실패로 덮어쓰지 않는다")
    void execute_success_cancelledWorker(boolean duringClassification) throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (LlmHttpServer server = new LlmHttpServer();
                SearchTransportCancellation cancellation = new SearchTransportCancellation(
                        access,
                        Duration.ofMillis(20)
                );
                SearchAgentHttp http = new SearchAgentHttp(
                        HttpClient.newHttpClient(),
                        JsonMapper.builder()
                                .build(),
                        Clock.systemUTC(),
                        262144,
                        cancellation
                );
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.respond(exchange -> {
                exchange.sendResponseHeaders(
                        200,
                        0
                );
                exchange.getResponseBody()
                        .flush();
                requested.countDown();
                try {
                    release.await(
                            10,
                            TimeUnit.SECONDS
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                }
            });
            if (duringClassification) {
                LlmProperties llm = new LlmProperties();
                llm.setBaseUrl(
                        server.baseUrl()
                                .resolve("/v1")
                );
                llm.setApiToken("test");
                llm.setModel("test-model");
                SearchQwenIntentClassifier delegate = new SearchQwenIntentClassifier(
                        new SearchLlmClient(
                                http,
                                llm
                        ),
                        history,
                        access,
                        new SearchAgentProperties(),
                        JsonMapper.builder()
                                .build()
                );
                when(
                        classifier.classify(
                                any(),
                                any()
                        )
                ).thenAnswer(
                        invocation -> delegate.classify(
                                invocation.getArgument(0),
                                invocation.getArgument(1)
                        )
                );
            }
            if (!duringClassification) {
                doAnswer(invocation -> {
                    SearchToolScope scope = invocation.getArgument(0);
                    executions.append(
                            scope.answerId(),
                            scope.token(),
                            "보존"
                    );
                    http.json(
                            server.baseUrl()
                                    .resolve("/v1/chat/completions"),
                            "test",
                            Map.of(),
                            scope,
                            Map.of()
                    );
                    throw new IllegalStateException("silent provider unexpectedly returned");
                }).when(retriever)
                        .retrieve(
                                any(),
                                any()
                        );
            }
            Future<?> running = executor.submit(() -> worker.execute(accepted.answerMessageId()));
            assertThat(
                    requested.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            String expectedContent = "";
            if (!duringClassification) {
                expectedContent = "보존";
            }
            assertThat(
                    executions.stop(
                            workspaceId,
                            memberId,
                            accepted.conversationId(),
                            accepted.answerMessageId()
                    )
                            .content()
            ).isEqualTo(expectedContent);
            running.get(
                    3,
                    TimeUnit.SECONDS
            );
            verifyNoInteractions(generator);
            if (duringClassification) {
                verifyNoInteractions(retriever);
                assertThat(server.requestBody()).contains("response_format");
            }
            assertThat(
                    jdbc.sql("SELECT status FROM search_messages WHERE role='ASSISTANT'")
                            .query(String.class)
                            .single()
            ).isEqualTo("STOPPED");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("중지한 token의 요청은 거절하고 다른 답변 연결은 정상 응답한다")
    void exchange_success_isolatesScopeAndToken() throws Exception {
        SearchToolScope stopped = scope();
        executions.stop(
                workspaceId,
                memberId,
                accepted.conversationId(),
                accepted.answerMessageId()
        );
        accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "다른 대화",
                        UUID.randomUUID()
                )
        );
        SearchToolScope healthy = scope();
        try (LlmHttpServer server = new LlmHttpServer();
                SearchTransportCancellation cancellation = new SearchTransportCancellation(
                        access,
                        Duration.ofMillis(20)
                );
                SearchAgentHttp http = new SearchAgentHttp(
                        HttpClient.newHttpClient(),
                        JsonMapper.builder()
                                .build(),
                        Clock.systemUTC(),
                        262144,
                        cancellation
                )) {
            server.respond(
                    200,
                    "{\"result\":true}"
            );
            assertThatThrownBy(
                    () -> call(
                            http,
                            server,
                            stopped,
                            "JSON"
                    )
            ).isInstanceOf(SearchToolException.class);
            assertThat(
                    http.json(
                            server.baseUrl()
                                    .resolve("/v1/chat/completions"),
                            "test",
                            Map.of(),
                            healthy,
                            Map.of()
                    )
                            .path("result")
                            .asBoolean()
            ).isTrue();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private SearchToolScope scope() {
        SearchAnswerWorkResult work = executions.claim(accepted.answerMessageId())
                .orElseThrow();
        Instant now = Instant.now();
        return SearchToolScope.from(
                work,
                now,
                now.plusSeconds(30)
        );
    }

    private void call(
            SearchAgentHttp http,
            LlmHttpServer server,
            SearchToolScope scope,
            String mode
    ) {
        if (mode.equals("MCP")) {
            SearchAgentProperties properties = new SearchAgentProperties();
            properties.setMcpBaseUrl(server.baseUrl());
            properties.setSigningKey("test-key-with-at-least-32-characters");
            JsonMapper mapper = JsonMapper.builder()
                    .build();
            SearchToolTokenCodec tokens = new SearchToolTokenCodec(
                    mapper,
                    properties,
                    Clock.systemUTC()
            );
            new SearchMcpClient(
                    http,
                    tokens,
                    properties,
                    mapper
            ).definitions(scope);
            return;
        }
        if (mode.equals("SSE")) {
            http.stream(
                    server.baseUrl()
                            .resolve("/v1/chat/completions"),
                    "test",
                    Map.of(),
                    scope,
                    chunk -> {
                    }
            );
            return;
        }
        if (mode.equals("NOTIFICATION")) {
            http.notification(
                    server.baseUrl()
                            .resolve("/v1/chat/completions"),
                    "test",
                    Map.of(
                            "method",
                            "notifications/initialized"
                    ),
                    scope,
                    Map.of()
            );
            return;
        }
        http.json(
                server.baseUrl()
                        .resolve("/v1/chat/completions"),
                "test",
                Map.of(),
                scope,
                Map.of()
        );
    }

    private String providerPath(String mode) {
        if (mode.equals("MCP")) {
            return "/internal/mcp";
        }
        return "/v1/chat/completions";
    }
}
