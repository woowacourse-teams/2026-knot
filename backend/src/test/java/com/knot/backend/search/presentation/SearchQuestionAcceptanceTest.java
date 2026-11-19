package com.knot.backend.search.presentation;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.infrastructure.llm.SearchExploration;
import com.knot.backend.search.infrastructure.llm.SearchLlmClient;
import com.knot.backend.search.infrastructure.llm.SearchStreamingAnswerGenerator;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
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
import org.junit.jupiter.params.provider.EnumSource;
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

    private enum Coverage {
        NO_MATCH,
        UNAVAILABLE,
        PARTIAL,
        BUDGET
    }

    @ParameterizedTest
    @EnumSource(Coverage.class)
    @DisplayName("검색 확인 범위에 맞는 본문·근거를 완료하고 재연결에서도 복구한다")
    void create_success_replaysSearchCoverage(Coverage coverage) throws Exception {
        String question = "1. 배포 회의의 결정\n2. 홍보 회의의 결정";
        List<Long> expectedIds = prepareCoverage(coverage);
        String expected = switch (coverage) {
            case NO_MATCH -> "검색한 범위에서 관련 회의 기록을 찾지 못했어요.";
            case UNAVAILABLE -> "지금은 회의 기록을 조회하기 어려워";
            case PARTIAL -> "요청의 2번째 항목은 회의 기록을 조회하지 못했어요.";
            case BUDGET -> "입력 한도 때문에 일부 기록만 확인했어요.";
        };
        UUID requestId = UUID.randomUUID();
        long started = System.nanoTime();
        long answer;
        String body;
        try (BufferedReader stream = reader(
                connect(
                        requestId,
                        question
                ).body()
        )) {
            assertThat(readEvent(stream)).startsWith("event:accepted");
            answer = answerId();
            worker.execute(answer);
            String event = readEvent(stream);
            while (!event.isEmpty() && !event.startsWith("event:delta")) {
                event = readEvent(stream);
            }
            long firstDeltaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(event).startsWith("event:delta");
            body = json.readTree(
                    event.lines()
                            .filter(line -> line.startsWith("data:"))
                            .findFirst()
                            .orElseThrow()
                            .substring("data:".length())
                            .strip()
            )
                    .path("text")
                    .asString();
            assertThat(body).contains(expected)
                    .doesNotContain("확인하지 못한 문서 제목");
            assertThat(
                    stream.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "event:evidence",
                    "event:completed"
            )
                    .doesNotContain("event:failed");
            System.out.printf(
                    "Search coverage %s firstDeltaMs=%d%n",
                    coverage,
                    firstDeltaMs
            );
        }
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                answer
                        )
                        .query(Long.class)
                        .list()
        ).containsExactlyElementsOf(expectedIds);
        HttpResponse<String> replay = client
                .send(
                        HttpRequest
                                .newBuilder(
                                        uri(
                                                path(workspaceId) + "/" + conversationId() + "/answers/" + answer
                                                        + "/events"
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
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains(
                expected,
                "event:completed"
        );
        assertThat(
                replay.body()
                        .split(
                                java.util.regex.Pattern.quote(expected),
                                -1
                        )
        ).hasSize(2);
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answer
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(body);
    }

    private List<Long> prepareCoverage(Coverage coverage) {
        if (coverage == Coverage.NO_MATCH) {
            when(
                    retriever.retrieve(
                            any(),
                            any()
                    )
            ).thenReturn(
                    new SearchRetrievalResult(
                            List.of(),
                            0
                    )
            );
            return List.of();
        }
        if (coverage == Coverage.UNAVAILABLE) {
            when(
                    retriever.retrieve(
                            any(),
                            any()
                    )
            ).thenThrow(new SearchToolException("SEARCH_TRANSPORT_FAILED"));
            return List.of();
        }
        long document = saveArchivedDocument(
                workspaceId,
                memberId
        );
        SearchDocumentResult first = new SearchDocumentResult(
                document,
                "배포 회의",
                "배포",
                "금요일 배포를 결정했다."
        );
        SearchStreamingAnswerGenerator actual = groundAnswerFrom(document);
        if (coverage == Coverage.PARTIAL) {
            when(
                    retriever.retrieve(
                            any(),
                            any()
                    )
            ).thenReturn(
                    new SearchRetrievalResult(
                            List.of(first),
                            0,
                            SearchRetrievalResult.Status.SEARCHED,
                            List.of(
                                    new SearchRetrievalResult.Item(
                                            1,
                                            SearchRetrievalResult.ItemStatus.MATCHED,
                                            List.of(document)
                                    ),
                                    new SearchRetrievalResult.Item(
                                            2,
                                            SearchRetrievalResult.ItemStatus.UNAVAILABLE,
                                            List.of()
                                    )
                            )
                    )
            );
            doAnswer(
                    call -> actual.generateFromRetrieval(
                            call.getArgument(0),
                            call.getArgument(1),
                            call.getArgument(2),
                            call.getArgument(3)
                    )
            ).when(generator)
                    .generateFromRetrieval(
                            any(),
                            any(),
                            any(),
                            any()
                    );
            return List.of(document);
        }
        long excluded = saveArchivedDocument(
                workspaceId,
                memberId
        );
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(
                                first,
                                new SearchDocumentResult(
                                        excluded,
                                        "확인하지 못한 문서 제목",
                                        "홍보",
                                        "긴 문서".repeat(12_000)
                                )
                        ),
                        0
                )
        );
        doAnswer(
                call -> actual.generate(
                        call.getArgument(0),
                        call.getArgument(1),
                        call.getArgument(2),
                        call.getArgument(3)
                )
        ).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );
        return List.of(document);
    }

    private SearchStreamingAnswerGenerator groundAnswerFrom(long document) {
        SearchLlmClient upstream = mock(SearchLlmClient.class);
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(2);
            delta.accept("""
                    {"status":"ANSWERED","decisionState":"DECIDED","answer":"금요일 배포를 결정했어요.",
                    "evidenceIds":["E%s"],"clarificationQuestion":null}
                    """.formatted(document));
            return null;
        }).when(upstream)
                .stream(
                        any(),
                        any(),
                        any()
                );
        SearchAgentProperties properties = new SearchAgentProperties();
        properties.setContextCharacterLimit(8_000);
        return new SearchStreamingAnswerGenerator(
                upstream,
                searchHistory,
                toolAccess,
                properties,
                json
        );
    }

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
            return SearchUsePlan.of(SearchIntent.OUT_OF_SCOPE);
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
    private SearchToolAccess toolAccess;
    @Autowired
    private SearchHistoryQuery searchHistory;
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
        ).thenReturn(SearchUsePlan.of(SearchIntent.MEETING_SEARCH));
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
        SearchSseTiming timing = new SearchSseTiming(json);
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
            Future<?> execution = executor.submit(() -> worker.execute(answerId));
            String rest = timing.readToEnd(reader);
            execution.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(timing.firstDelta()).isPresent();
            assertThat(timing.terminalEvent()).contains("completed");
            timing.log("initial");
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
    @DisplayName("결과 없음과 해당 없음 상태의 모델 응답은 근거를 붙이지 않고 SSE를 완료한다")
    void create_success_notFoundWithNotApplicable() throws Exception {
        String question = "회의 자료에 외부 배포 계획이 있어?";
        String expected = "현재 회의 문서에서 외부 배포 계획을 찾지 못했습니다.";
        long documentId = new SearchMessageFixtures(jdbc).saveDocument(
                workspaceId,
                memberId
        );
        List<SearchDocumentResult> documents = List.of(
                new SearchDocumentResult(
                        documentId,
                        "탐색 자료",
                        "회의",
                        "내부 회의 내용을 기록한다."
                )
        );
        when(
                retriever.retrieve(
                        any(),
                        eq(question)
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        documents,
                        0
                )
        );
        SearchLlmClient upstream = mock(SearchLlmClient.class);
        doAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(2);
            delta.accept(
                    """
                            {"status":"NOT_FOUND","decisionState":"NOT_APPLICABLE","answer":"%s","evidenceIds":[],"clarificationQuestion":null}
                            """
                            .formatted(expected)
            );
            return null;
        }).when(upstream)
                .stream(
                        any(),
                        any(),
                        any()
                );
        SearchStreamingAnswerGenerator actual = new SearchStreamingAnswerGenerator(
                upstream,
                searchHistory,
                toolAccess,
                new SearchAgentProperties(),
                json
        );
        doAnswer(
                invocation -> actual.generate(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)
                )
        ).when(generator)
                .generate(
                        any(),
                        eq(question),
                        eq(documents),
                        any()
                );

        try (InputStream stream = connect(
                UUID.randomUUID(),
                question
        ).body(); BufferedReader reader = reader(stream)) {
            assertThat(readEvent(reader)).startsWith("event:accepted");
            worker.execute(answerId());
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "event:delta",
                    expected,
                    "event:completed"
            )
                    .doesNotContain("event:failed");
        }
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_evidences")
                        .query(Long.class)
                        .single()
        ).isZero();
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
        ).thenReturn(SearchUsePlan.of(SearchIntent.OUT_OF_SCOPE));
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
        ).thenReturn(SearchUsePlan.of(SearchIntent.GREETING_OR_THANKS));
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
