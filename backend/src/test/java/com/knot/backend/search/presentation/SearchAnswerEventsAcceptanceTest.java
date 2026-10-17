package com.knot.backend.search.presentation;

import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.application.SearchIntentClassifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerExecutionService;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knot.search.worker.stream-timeout=1s", "knot.search.worker.stream-heartbeat-interval=50ms",
        "knot.search.worker.stream-concurrency=2"})
@Import({TestcontainersConfiguration.class, SearchAnswerEventsAcceptanceTest.RequestObserverConfiguration.class})
@TestApplicationProperties
@Timeout(30)
class SearchAnswerEventsAcceptanceTest {
    private static final Duration STREAM_COMPLETION_WAIT = Duration.ofSeconds(5);
    private static final Duration STREAM_EXECUTOR_POLL_INTERVAL = Duration.ofMillis(10);

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JwtProperties jwt;
    @Autowired
    private SearchWorkerProperties searchWorkerProperties;
    @Autowired
    private JsonMapper json;
    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    @Qualifier("searchStreamExecutor")
    private ThreadPoolTaskExecutor streamExecutor;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchAnswerGenerator generator;
    @Autowired
    private RequestObserver requestObserver;
    private final HttpClient client = HttpClient.newHttpClient();
    private DocumentFixtures fixtures;
    private long memberId;
    private long workspaceId;
    private String cookie;
    private SearchQuestionAcceptedResult accepted;

    @BeforeEach
    void setUp() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("질문자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        cookie = cookie(memberId);
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
    @ValueSource(strings = {"COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("종료된 답변의 본문·근거 상위 3개·상태를 실제 HTTP로 복구한다")
    void reconnect_success_terminal(String status) throws Exception {
        jdbc.sql("UPDATE search_messages SET content='한글 😀', status=:status, failure_code=:code WHERE id=:id")
                .param(
                        "status",
                        status
                )
                .param(
                        "code",
                        failureCode(status)
                )
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .update();
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        for (int rank = 1; rank <= 4; rank++) {
            messages.saveEvidence(
                    accepted.answerMessageId(),
                    messages.saveDocument(
                            workspaceId,
                            memberId
                    ),
                    rank
            );
        }
        HttpResponse<InputStream> response = connect(
                cookie,
                accepted.conversationId(),
                accepted.answerMessageId()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
        ).get()
                .asString()
                .startsWith("text/event-stream");
        assertThat(
                response.headers()
                        .firstValue("Cache-Control")
        ).contains("no-cache");
        assertThat(
                response.headers()
                        .firstValue("X-Accel-Buffering")
        ).contains("no");
        try (BufferedReader reader = reader(response.body())) {
            String snapshot = readEvent(reader);
            assertThat(snapshot).startsWith("event:snapshot")
                    .contains(
                            "\"answerId\":",
                            "한글 😀",
                            status
                    );
            JsonNode evidence = payload(readEvent(reader));
            assertThat(
                    evidence.get("items")
                            .size()
            ).isEqualTo(3);
            assertThat(
                    evidence.get("items")
                            .get(2)
                            .get("rank")
                            .asInt()
            ).isEqualTo(3);
            String terminal = readEvent(reader);
            assertThat(terminal).startsWith("event:" + status.toLowerCase())
                    .doesNotContain("answerMessageId");
            if (status.equals("FAILED")) {
                assertThat(terminal).contains("ANSWER_TIMEOUT");
            }
            assertThat(reader.readLine()).isNull();
        }
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_evidences")
                        .query(Long.class)
                        .single()
        ).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unauthenticated", "owner", "membership", "workspace", "missing", "conversation",
            "question", "mismatch", "invalid"})
    @DisplayName("SSE Accept에서도 인증·권한·역할·소속 오류를 시작 전 JSON으로 반환한다")
    void reconnect_failure_access(String scenario) throws Exception {
        String requestCookie = cookie;
        long conversation = accepted.conversationId();
        long answer = accepted.answerMessageId();
        int expected = 404;
        String code = "ANSWER_NOT_FOUND";
        if (scenario.equals("unauthenticated")) {
            requestCookie = "";
            expected = 401;
            code = "UNAUTHENTICATED";
        }
        if (scenario.equals("owner")) {
            long other = fixtures.saveMember("다른 멤버");
            fixtures.join(
                    workspaceId,
                    other
            );
            requestCookie = cookie(other);
            expected = 403;
            code = "SEARCH_ACCESS_DENIED";
        }
        if (scenario.equals("membership")) {
            jdbc.sql("DELETE FROM workspace_members WHERE workspace_id=:id")
                    .param(
                            "id",
                            workspaceId
                    )
                    .update();
            expected = 403;
            code = "SEARCH_ACCESS_DENIED";
        }
        if (scenario.equals("workspace")) {
            workspaceId = fixtures.saveWorkspace();
            expected = 403;
            code = "SEARCH_ACCESS_DENIED";
        }
        if (scenario.equals("missing")) {
            answer = 9999;
        }
        if (scenario.equals("conversation")) {
            conversation = 9999;
        }
        if (scenario.equals("question")) {
            answer = accepted.questionMessageId();
        }
        if (scenario.equals("mismatch")) {
            conversation = intake.acceptFirst(
                    workspaceId,
                    memberId,
                    new SearchQuestionCommand(
                            "다른 질문",
                            UUID.randomUUID()
                    )
            )
                    .conversationId();
        }
        String path = path(
                conversation,
                answer
        );
        if (scenario.equals("invalid")) {
            path = path(
                    conversation,
                    answer
            ).replace(
                    "/answers/" + answer + "/events",
                    "/answers/invalid/events"
            );
            expected = 400;
            code = "INVALID_PARAMETER";
        }
        HttpResponse<String> response = client.send(
                request(
                        path,
                        requestCookie
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
        ).get()
                .asString()
                .startsWith("application/json");
        assertThat(response.body()).contains(code)
                .doesNotContain("event:snapshot");
    }

    @Test
    @DisplayName("두 구독 중 하나를 끊어도 생성은 계속되고 새 연결은 저장된 본문을 이어 받는다")
    void reconnect_success_multipleSubscribers() throws Exception {
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspaceId,
                memberId
        );
        jdbc.sql("UPDATE documents SET status='ARCHIVED', archived_at=created_at WHERE id=:id")
                .param(
                        "id",
                        document
                )
                .update();
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(
                                new SearchDocumentResult(
                                        document,
                                        "문서",
                                        "주제",
                                        "본문"
                                )
                        ),
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("처음 😀");
            generating.countDown();
            assertThat(
                    resume.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            delta.accept(" 이어짐");
            return List.of(document);
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );
        try (java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<?> running = executor.submit(() -> worker.execute(accepted.answerMessageId()));
            try {
                assertThat(
                        generating.await(
                                5,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                try (BufferedReader first = reader(
                        connect(
                                cookie,
                                accepted.conversationId(),
                                accepted.answerMessageId()
                        ).body()
                );
                        BufferedReader second = reader(
                                connect(
                                        cookie,
                                        accepted.conversationId(),
                                        accepted.answerMessageId()
                                ).body()
                        )) {
                    assertThat(readEvent(first)).contains("처음 😀");
                    String initial = payload(readEvent(second)).get("content")
                            .asString();
                    first.close();
                    resume.countDown();
                    running.get(
                            5,
                            TimeUnit.SECONDS
                    );
                    List<String> remaining = readRemaining(second);
                    String delta = remaining.stream()
                            .filter(event -> event.startsWith("event:delta"))
                            .map(
                                    event -> payload(event).get("text")
                                            .asString()
                            )
                            .collect(Collectors.joining());
                    assertThat(initial + delta).isEqualTo("처음 😀 이어짐");
                    assertThat(
                            String.join(
                                    "",
                                    remaining
                            )
                    ).contains("event:completed")
                            .doesNotContain(
                                    "accepted",
                                    "progress",
                                    "excluded_documents",
                                    "answerMessageId"
                            );
                }
                try (BufferedReader restored = reader(
                        connect(
                                cookie,
                                accepted.conversationId(),
                                accepted.answerMessageId()
                        ).body()
                )) {
                    assertThat(readEvent(restored)).contains(
                            "처음 😀 이어짐",
                            "COMPLETED"
                    );
                    assertThat(
                            String.join(
                                    "",
                                    readRemaining(restored)
                            )
                    ).contains("event:completed");
                }
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    @DisplayName("무출력 연결은 heartbeat 후 timeout으로 닫히고 저장 상태를 바꾸지 않는다")
    @ExtendWith(OutputCaptureExtension.class)
    void reconnect_success_timeoutCleanup(CapturedOutput output) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            long startedAt = System.nanoTime();
            try (BufferedReader reader = reader(
                    connect(
                            cookie,
                            accepted.conversationId(),
                            accepted.answerMessageId()
                    ).body()
            )) {
                assertThat(readEvent(reader)).contains("STREAMING");
                assertThat(
                        String.join(
                                "",
                                readRemaining(reader)
                        )
                ).contains(":keepalive")
                        .doesNotContain("event:failed");
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                        .isGreaterThanOrEqualTo(searchWorkerProperties.getStreamTimeout());
            }
            awaitStreamExecutorIdle();
        }
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE id=:id")
                        .param(
                                "id",
                                accepted.answerMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("STREAMING");
        assertThat(output.getAll()).doesNotContain("AsyncRequestTimeoutException");
    }

    private void awaitStreamExecutorIdle() throws InterruptedException {
        long deadline = System.nanoTime() + STREAM_COMPLETION_WAIT.toNanos();
        while (!isStreamExecutorIdle() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(STREAM_EXECUTOR_POLL_INTERVAL.toMillis());
        }
        assertThat(streamExecutor.getActiveCount()).isZero();
        assertThat(
                streamExecutor.getThreadPoolExecutor()
                        .getQueue()
        ).isEmpty();
    }

    private boolean isStreamExecutorIdle() {
        return streamExecutor.getActiveCount() == 0 && streamExecutor.getThreadPoolExecutor()
                .getQueue()
                .isEmpty();
    }

    private String failureCode(String status) {
        if (status.equals("FAILED")) {
            return "ANSWER_TIMEOUT";
        }
        return null;
    }

    @Test
    @DisplayName("실제 소켓이 큰 응답을 읽지 않아도 다른 구독은 완료되고 단절 후 전송 작업이 회수된다")
    void reconnect_success_unreadSocketIsolation() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        requestObserver.arm(disconnected);
        String content = "a".repeat(8_000_000);
        jdbc.sql("UPDATE search_messages SET content=:content, status='COMPLETED' WHERE id=:id")
                .param(
                        "content",
                        content
                )
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .update();
        try (Socket slow = new Socket()) {
            slow.setReceiveBufferSize(1024);
            slow.setSoLinger(
                    true,
                    0
            );
            slow.setSoTimeout(5000);
            slow.connect(
                    new InetSocketAddress(
                            "localhost",
                            port
                    )
            );
            slow.getOutputStream()
                    .write(
                            ("GET " + path(
                                    accepted.conversationId(),
                                    accepted.answerMessageId()
                            ) + " HTTP/1.1\r\nHost: localhost\r\nCookie: " + cookie
                                    + "\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8)
                    );
            BufferedReader headers = reader(slow.getInputStream());
            assertThat(headers.readLine()).contains("200");
            for (String line = headers.readLine(); line != null && !line.isEmpty(); line = headers.readLine()) {
                assertThat(line).isNotNull();
            }
            try (BufferedReader fast = reader(
                    connect(
                            cookie,
                            accepted.conversationId(),
                            accepted.answerMessageId()
                    ).body()
            )) {
                JsonNode snapshot = payload(readEvent(fast));
                JsonNode restored = snapshot.get("content");
                assertThat(restored.asString()).isEqualTo(content);
                assertThat(readEvent(fast)).startsWith("event:completed");
                assertThat(fast.readLine()).isNull();
            }
            assertThat(slow.isClosed()).isFalse();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5)
                .toNanos();
        while (streamExecutor.getActiveCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(streamExecutor.getActiveCount()).isZero();
        assertThat(
                disconnected.await(
                        5,
                        TimeUnit.SECONDS
                )
        ).as("단절된 초기 snapshot 요청의 Servlet completion")
                .isTrue();
    }

    @TestConfiguration
    static class RequestObserverConfiguration {

        @Bean
        RequestObserver requestObserver() {
            return new RequestObserver();
        }

        @Bean
        ServletListenerRegistrationBean<RequestObserver> requestObserverRegistration(RequestObserver observer) {
            return new ServletListenerRegistrationBean<>(observer);
        }
    }

    static class RequestObserver implements ServletRequestListener {
        private static final String ATTRIBUTE = RequestObserver.class.getName();

        private final AtomicReference<CountDownLatch> next = new AtomicReference<>();

        void arm(CountDownLatch completion) {
            next.set(completion);
        }

        @Override
        public void requestInitialized(ServletRequestEvent event) {
            CountDownLatch completion = next.getAndSet(null);
            if (completion != null) {
                event.getServletRequest()
                        .setAttribute(
                                ATTRIBUTE,
                                completion
                        );
            }
        }

        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            CountDownLatch completion = (CountDownLatch) event.getServletRequest()
                    .getAttribute(ATTRIBUTE);
            if (completion != null) {
                completion.countDown();
            }
        }
    }

    private HttpResponse<InputStream> connect(
            String access,
            long conversation,
            long answer
    ) throws Exception {
        HttpResponse<InputStream> response = client.send(
                request(
                        path(
                                conversation,
                                answer
                        ),
                        access
                ),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
        ).hasValueSatisfying(value -> assertThat(value).startsWith("text/event-stream"));
        return response;
    }

    private HttpRequest request(
            String path,
            String access
    ) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header(
                        "Accept",
                        "text/event-stream"
                )
                .header(
                        "Cookie",
                        access
                )
                .GET()
                .build();
    }

    private String path(
            long conversation,
            long answer
    ) {
        return "/api/v1/workspaces/" + workspaceId + "/search/conversations/" + conversation + "/answers/" + answer
                + "/events";
    }

    private String cookie(long id) {
        return jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        id,
                        "멤버",
                        null
                )
        );
    }

    private BufferedReader reader(InputStream input) {
        return new BufferedReader(
                new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                )
        );
    }

    private String readEvent(BufferedReader reader) throws Exception {
        StringBuilder event = new StringBuilder();
        for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
            event.append(line)
                    .append("\n");
        }
        return event.toString();
    }

    private List<String> readRemaining(BufferedReader reader) throws Exception {
        List<String> events = new ArrayList<>();
        for (String event = readEvent(reader); !event.isEmpty(); event = readEvent(reader)) {
            events.add(event);
        }
        return events;
    }

    private JsonNode payload(String event) {
        return SearchSseEventPayload.read(
                event,
                json
        );
    }
}
