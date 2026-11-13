package com.knot.backend.search.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.infrastructure.llm.SearchExploration;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(30)
class SearchQuestionAcceptanceTest {

    @Test
    @DisplayName("실제 SSE 접수 뒤 분류 대기 중에는 DB 본문이 없고 판정 후에만 안내를 전송한다")
    void create_success_classificationBarrier() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenAnswer(call -> {
            entered.countDown();
            assertThat(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            return SearchIntent.OUT_OF_SCOPE;
        });
        try (BufferedReader stream = reader(
                connect(
                        UUID.randomUUID(),
                        "케이크 만들어줘"
                ).body()
        ); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(readEvent(stream)).startsWith("event:accepted");
            long answer = answerId();
            Future<?> task = executor.submit(() -> worker.execute(answer));
            try {
                assertThat(
                        entered.await(
                                5,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                assertThat(
                        jdbc.sql("SELECT content FROM search_messages WHERE id=:id")
                                .param(
                                        "id",
                                        answer
                                )
                                .query(String.class)
                                .single()
                ).isEmpty();
                assertThat(status()).isEqualTo("STREAMING");
                verifyNoInteractions(
                        retriever,
                        generator
                );
            } finally {
                release.countDown();
            }
            task.get(
                    5,
                    TimeUnit.SECONDS
            );
            String events = stream.lines()
                    .collect(Collectors.joining("\n"));
            assertThat(events).contains(
                    "event:delta",
                    "일반 대화 답변",
                    "event:completed"
            )
                    .doesNotContain(
                            "OUT_OF_SCOPE",
                            "케이크 레시피",
                            "event:failed"
                    );
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JwtProperties jwt;
    @Autowired
    private JsonMapper json;
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
    private HttpClient client;
    private long workspaceId;
    private long memberId;
    private String csrfToken;
    private String csrfCookie;
    private String accessCookie;

    @AfterEach
    void awaitStreamsIdle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5)
                .toNanos();
        while (!streamsAreIdle() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(streamExecutor.getActiveCount()).as("다음 TRUNCATE 전에 이전 SSE 작업이 종료되어야 한다")
                .isZero();
        assertThat(streamExecutor.getQueueSize()).isZero();
    }

    private boolean streamsAreIdle() {
        return streamExecutor.getActiveCount() == 0 && streamExecutor.getQueueSize() == 0;
    }

    @BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.doAnswer(call -> {
            java.util.function.Consumer<String> delta = call.getArgument(3);
            delta.accept("일반 대화 답변");
            return List.of();
        })
                .when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );

        awaitStreamsIdle();
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("질문자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(csrf.statusCode()).isEqualTo(200);
        JsonNode csrfBody = json.readTree(csrf.body());
        JsonNode tokenNode = csrfBody.get("token");
        csrfToken = tokenNode.asString();
        csrfCookie = csrf.headers()
                .allValues("Set-Cookie")
                .stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow()
                .split(";")[0];
        accessCookie = jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        memberId,
                        "질문자",
                        null
                )
        );
    }

    @Test
    @DisplayName("실제 HTTP 첫 질문에서 DRAFT 근거·한글 delta·완료와 EOF를 받는다")
    void create_success_realSseAndUtf8() throws Exception {
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspaceId,
                memberId
        );
        List<SearchDocumentResult> documents = List.of(
                new SearchDocumentResult(
                        document,
                        "문서",
                        "주제",
                        "본문"
                )
        );
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        documents,
                        2
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("한글 😀");
            return List.of(document);
        }).when(generator)
                .generate(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문"),
                        org.mockito.ArgumentMatchers.eq(documents),
                        org.mockito.ArgumentMatchers.any()
                );
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                InputStream stream = connect(
                        UUID.randomUUID(),
                        "질문"
                ).body();
                BufferedReader reader = reader(stream)) {
            String accepted = readEvent(reader);
            assertThat(accepted).startsWith("event:accepted\n");
            assertThat(accepted).contains(
                    "conversationId",
                    "questionMessageId",
                    "answerMessageId"
            );
            long answerId = answerId();
            executor.submit(() -> worker.execute(answerId))
                    .get(
                            10,
                            TimeUnit.SECONDS
                    );
            String rest = reader.lines()
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(rest).contains(
                    "event:delta",
                    "한글 😀",
                    "event:evidence",
                    "\"rank\":1",
                    "event:excluded_documents",
                    "\"count\":2",
                    "event:completed"
            );
            assertThat(rest).doesNotContain(
                    "event:failed",
                    "FORBIDDEN",
                    "UNAUTHENTICATED"
            );
            assertThat(status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("연결을 닫아도 생성은 계속되고 저장 본문을 GET으로 복구할 수 있다")
    void create_success_disconnectDoesNotCancel() throws Exception {
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        long document = saveArchivedDocument(
                workspaceId,
                memberId
        );
        List<SearchDocumentResult> docs = List.of(
                new SearchDocumentResult(
                        document,
                        "문서",
                        "주제",
                        "본문"
                )
        );
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        docs,
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("부분 ");
            generating.countDown();
            assertThat(
                    resume.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            delta.accept("완료 😀");
            return List.of(document);
        }).when(generator)
                .generate(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문"),
                        org.mockito.ArgumentMatchers.eq(docs),
                        org.mockito.ArgumentMatchers.any()
                );
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            HttpResponse<InputStream> response = connect(
                    UUID.randomUUID(),
                    "질문"
            );
            BufferedReader reader = reader(response.body());
            assertThat(readEvent(reader)).startsWith("event:accepted");
            long answer = answerId();
            Future<?> result = executor.submit(() -> worker.execute(answer));
            assertThat(
                    generating.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            reader.close();
            resume.countDown();
            result.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(status()).isEqualTo("COMPLETED");
            long conversation = conversationId();
            HttpResponse<String> messages = client.send(
                    HttpRequest
                            .newBuilder(
                                    uri(
                                            "/api/v1/workspaces/" + workspaceId + "/search/conversations/"
                                                    + conversation + "/messages"
                                    )
                            )
                            .header(
                                    "Cookie",
                                    accessCookie
                            )
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(messages.statusCode()).isEqualTo(200);
            assertThat(messages.body()).contains(
                    "부분 완료 😀",
                    "COMPLETED"
            );
            HttpResponse<InputStream> restored = client.send(
                    HttpRequest
                            .newBuilder(
                                    uri(
                                            "/api/v1/workspaces/" + workspaceId + "/search/conversations/"
                                                    + conversation + "/answers/" + answer + "/events"
                                    )
                            )
                            .header(
                                    "Cookie",
                                    accessCookie
                            )
                            .header(
                                    "Accept",
                                    "text/event-stream"
                            )
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            assertThat(restored.statusCode()).isEqualTo(200);
            try (BufferedReader replay = reader(restored.body())) {
                assertThat(readEvent(replay)).startsWith("event:snapshot")
                        .contains("부분 완료 😀");
                assertThat(
                        replay.lines()
                                .collect(java.util.stream.Collectors.joining("\n"))
                ).contains("event:completed");
            }
        }
    }

    @Test
    @DisplayName("응답 유실 뒤 동일 POST는 기존 ID와 저장 본문·종료 상태를 복구한다")
    void create_success_duplicateRecoversTerminalBody() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(),
                        0
                )
        );
        try (InputStream first = connect(
                requestId,
                "질문"
        ).body(); BufferedReader reader = reader(first)) {
            assertThat(readEvent(reader)).startsWith("event:accepted");
        }
        worker.execute(answerId());
        HttpResponse<String> duplicate = client.send(
                request(
                        requestId,
                        "질문",
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.body()).startsWith("event:accepted");
        assertThat(duplicate.body()).contains(
                "event:delta",
                "일반 대화 답변",
                "event:completed"
        );
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_conversations")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("목록에 문서가 있어도 검색 결과가 없으면 실제 SSE로 안내와 빈 근거를 보내고 정상 완료한다")
    void create_success_noSearchMatchesWithListedDocuments() throws Exception {
        // given
        long document = saveArchivedDocument(
                workspaceId,
                memberId
        );
        when(
                retriever.retrieve(
                        any(),
                        eq("점심")
                )
        ).thenAnswer(call -> {
            SearchExploration exploration = new SearchExploration();
            exploration.record(
                    "search_documents",
                    json.readTree("{\"items\":[]}")
            );
            exploration.record(
                    "list_documents",
                    json.readTree("{\"items\":[{\"documentId\":" + document + "}]}")
            );
            return new SearchRetrievalResult(
                    exploration.finish(),
                    0
            );
        });

        // when
        try (BufferedReader stream = reader(
                connect(
                        UUID.randomUUID(),
                        "점심"
                ).body()
        )) {
            assertThat(readEvent(stream)).startsWith("event:accepted");
            long answer = answerId();
            worker.execute(answer);
            String events = stream.lines()
                    .collect(Collectors.joining("\n"));

            // then
            assertThat(events).contains(
                    "event:delta",
                    "일반 대화 답변",
                    "event:evidence",
                    "\"items\":[]",
                    "event:completed",
                    "\"status\":\"COMPLETED\""
            )
                    .doesNotContain("event:failed");
            assertThat(
                    jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(String.class)
                            .single()
            ).isEqualTo("COMPLETED");
            verify(generator).generate(
                    any(),
                    any(),
                    eq(List.of()),
                    any()
            );
        }
    }

    @Test
    @DisplayName("일반 요청은 일반 답변으로 완료한다")
    void create_success_outOfScopeFixedGuidance() throws Exception {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.OUT_OF_SCOPE);
        try (BufferedReader stream = reader(
                connect(
                        UUID.randomUUID(),
                        "지난 회의 담당자 찾아줘. 그리고 케이크 만들어줘"
                ).body()
        )) {
            assertThat(readEvent(stream)).startsWith("event:accepted");
            worker.execute(answerId());
            String events = stream.lines()
                    .collect(Collectors.joining("\n"));
            assertThat(events).contains(
                    "event:delta",
                    "일반 대화 답변",
                    "event:completed",
                    "\"status\":\"COMPLETED\"",
                    "\"items\":[]"
            )
                    .doesNotContain(
                            "event:failed",
                            "케이크 레시피",
                            "OUT_OF_SCOPE"
                    );
            assertThat(status()).isEqualTo("COMPLETED");
            verifyNoInteractions(retriever);
            verify(generator).generate(
                    any(),
                    any(),
                    eq(List.of()),
                    any()
            );
        }
    }

    @Test
    @DisplayName("인사는 모델 답변과 빈 근거로 완료한다")
    void create_success_greetingWithoutDocumentSearch() throws Exception {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.GREETING_OR_THANKS);
        try (BufferedReader stream = reader(
                connect(
                        UUID.randomUUID(),
                        "반갑다"
                ).body()
        )) {
            assertThat(readEvent(stream)).startsWith("event:accepted");
            worker.execute(answerId());
            String events = stream.lines()
                    .collect(Collectors.joining("\n"));
            assertThat(events).contains(
                    "일반 대화 답변",
                    "event:completed",
                    "\"items\":[]"
            )
                    .doesNotContain(
                            "event:failed",
                            "answer_without_search"
                    );
            verifyNoInteractions(retriever);
            verify(generator).generate(
                    any(),
                    any(),
                    eq(List.of()),
                    any()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("분류·검색 오류에도 내부 오류 유출 없이 일반 답변으로 이어간다")
    void create_failure_afterAccepted(boolean duringClassification) throws Exception {
        if (duringClassification) {
            when(
                    classifier.classify(
                            any(),
                            any()
                    )
            ).thenThrow(new SearchToolException("private secret"));
        }
        if (!duringClassification) {
            when(
                    retriever.retrieve(
                            org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                            org.mockito.ArgumentMatchers.eq("질문")
                    )
            ).thenThrow(new SearchToolException("private secret"));
        }
        try (InputStream stream = connect(
                UUID.randomUUID(),
                "질문"
        ).body(); BufferedReader reader = reader(stream)) {
            assertThat(readEvent(reader)).startsWith("event:accepted");
            worker.execute(answerId());
            String events = reader.lines()
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(events).contains(
                    "event:completed",
                    "일반 대화 답변"
            )
                    .doesNotContain(
                            "private secret",
                            "event:failed"
                    );
            assertThat(status()).isEqualTo("COMPLETED");
            verify(generator).generate(
                    any(),
                    any(),
                    eq(List.of()),
                    any()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u00a0"})
    @DisplayName("SSE Accept 요청의 빈 질문도 JSON VALIDATION_ERROR로 거절하고 저장하지 않는다")
    void create_failure_invalidContent(String content) throws Exception {
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        content,
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
        ).contains("application/json");
        assertThat(response.body()).contains("VALIDATION_ERROR");
        assertNoConversations();
    }

    @Test
    @DisplayName("잘못된 UUID도 VALIDATION_ERROR로 반환한다")
    void create_failure_invalidUuid() throws Exception {
        HttpRequest invalid = HttpRequest.newBuilder(uri(path(workspaceId)))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "text/event-stream"
                )
                .header(
                        "Cookie",
                        csrfCookie + "; " + accessCookie
                )
                .header(
                        "X-XSRF-TOKEN",
                        csrfToken
                )
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"질문\",\"requestId\":\"1-1-1-1-1\"}"))
                .build();
        HttpResponse<String> response = client.send(
                invalid,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_ERROR");
        assertNoConversations();
    }

    @Test
    @DisplayName("CSRF가 유효해도 로그인 쿠키가 없으면 401이다")
    void create_failure_unauthenticated() throws Exception {
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        "질문",
                        workspaceId,
                        false,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("UNAUTHENTICATED");
        assertNoConversations();
    }

    @Test
    @DisplayName("로그인되어도 CSRF가 없으면 기존 Security 규칙의 403 FORBIDDEN이다")
    void create_failure_csrf() throws Exception {
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        "질문",
                        workspaceId,
                        true,
                        false
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("FORBIDDEN");
        assertNoConversations();
    }

    @Test
    @DisplayName("Workspace 현재 멤버가 아니면 403으로 접수하지 않는다")
    void create_failure_nonMember() throws Exception {
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        "질문",
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("WORKSPACE_ACCESS_DENIED");
        assertNoConversations();
    }

    @Test
    @DisplayName("다른 본문 재접수는 HTTP 409 JSON으로 거절하고 기존 접수만 남긴다")
    void create_failure_requestIdConflict() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(),
                        0
                )
        );
        try (InputStream first = connect(
                requestId,
                "질문"
        ).body(); BufferedReader reader = reader(first)) {
            assertThat(readEvent(reader)).startsWith("event:accepted");
        }
        worker.execute(answerId());
        HttpResponse<String> response = client.send(
                request(
                        requestId,
                        "다른 질문",
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("REQUEST_ID_CONFLICT");
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_messages")
                        .query(Long.class)
                        .single()
        ).isEqualTo(2);
    }

    @Test
    @DisplayName("2,001 이모지 질문은 UTF-16 길이와 관계없이 접수 전 400으로 거절한다")
    void create_failure_questionTooLong() throws Exception {
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        "😀".repeat(2001),
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_ERROR");
        assertNoConversations();
    }

    @Test
    @DisplayName("생성이 준비되지 않았을 때 SSE Accept 요청도 JSON 오류로 접수 전 실패한다")
    void create_failure_generationUnavailable() throws Exception {
        org.mockito.Mockito
                .doThrow(
                        new com.knot.backend.search.domain.SearchException(
                                com.knot.backend.search.domain.SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE
                        )
                )
                .when(availability)
                .requireAvailable();
        HttpResponse<String> response = client.send(
                request(
                        UUID.randomUUID(),
                        "질문",
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("ANSWER_GENERATION_UNAVAILABLE");
        assertNoConversations();
    }

    @Test
    @DisplayName("숫자 질문을 JSON 문자열로 바꾸지 않고 400으로 거절한다")
    void create_failure_nonStringJson() throws Exception {
        HttpRequest invalid = HttpRequest.newBuilder(uri(path(workspaceId)))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "text/event-stream"
                )
                .header(
                        "Cookie",
                        csrfCookie + "; " + accessCookie
                )
                .header(
                        "X-XSRF-TOKEN",
                        csrfToken
                )
                .POST(
                        HttpRequest.BodyPublishers
                                .ofString("{\"content\":123,\"requestId\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\"}")
                )
                .build();
        HttpResponse<String> response = client.send(
                invalid,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST_BODY");
        assertNoConversations();
    }

    private HttpResponse<InputStream> connect(
            UUID requestId,
            String content
    ) throws Exception {
        HttpResponse<InputStream> response = client.send(
                request(
                        requestId,
                        content,
                        workspaceId,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
        ).contains("text/event-stream");
        return response;
    }

    private HttpRequest request(
            UUID requestId,
            String content,
            long workspace,
            boolean authenticated,
            boolean csrf
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path(workspace)))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "text/event-stream"
                );
        String cookies = csrfCookie;
        if (authenticated) {
            cookies += "; " + accessCookie;
        }
        builder.header(
                "Cookie",
                cookies
        );
        if (csrf) {
            builder.header(
                    "X-XSRF-TOKEN",
                    csrfToken
            );
        }
        return builder.POST(
                HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                                java.util.Map.of(
                                        "content",
                                        content,
                                        "requestId",
                                        requestId.toString()
                                )
                        )
                )
        )
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    private String path(long workspace) {
        return "/api/v1/workspaces/" + workspace + "/search/conversations";
    }

    private BufferedReader reader(InputStream stream) {
        return new BufferedReader(
                new InputStreamReader(
                        stream,
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

    private long answerId() {
        return jdbc.sql("SELECT id FROM search_messages WHERE role = 'ASSISTANT'")
                .query(Long.class)
                .single();
    }

    private long conversationId() {
        return jdbc.sql("SELECT id FROM search_conversations")
                .query(Long.class)
                .single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM search_messages WHERE role = 'ASSISTANT'")
                .query(String.class)
                .single();
    }

    private void assertNoConversations() {
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_conversations")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_messages")
                        .query(Long.class)
                        .single()
        ).isZero();
    }
    private long saveArchivedDocument(
            long workspace,
            long member
    ) {
        long id = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
                .param(
                        "id",
                        id
                )
                .update();
        return id;
    }
}
