package com.knot.backend.search.presentation;

import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.application.SearchIntentClassifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import com.knot.backend.search.application.SearchAnswerGuidance;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
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
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knot.search.worker.stream-timeout=3s"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(30)
class SearchFollowupQuestionAcceptanceTest {

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
    private SearchWorkerProperties streamLimits;
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
    private DocumentFixtures fixtures;
    private long workspace;
    private long member;
    private long conversation;
    private String csrfToken;
    private String cookies;
    private String csrfCookie;

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

        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        member = fixtures.saveMember("후속 질문자");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        conversation = new SearchFixtures(jdbc).saveConversation(
                workspace,
                member,
                "이전 질문",
                "이전 답변"
        );
        jdbc.sql("UPDATE search_messages SET status = 'COMPLETED' WHERE role = 'ASSISTANT'")
                .update();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        csrfToken = json.readTree(csrf.body())
                .path("token")
                .asString();
        csrfCookie = csrf.headers()
                .allValues("Set-Cookie")
                .stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow()
                .split(";")[0];
        cookies = csrfCookie + "; " + jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        member,
                        "후속 질문자",
                        null
                )
        );
        when(
                retriever.retrieve(
                        any(SearchToolScope.class),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(),
                        0
                )
        );
    }

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

    @Test
    @DisplayName("후속 POST는 accepted를 먼저 보내고 생성 완료와 같은 ID 재접수를 복구한다")
    void ask_success_sseAndReplay() throws Exception {
        // given
        UUID request = UUID.randomUUID();
        // when
        try (InputStream stream = connect(
                body(
                        request,
                        "후속 😀"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            JsonNode accepted = accepted(reader);
            assertThat(
                    accepted.path("conversationId")
                            .asLong()
            ).isEqualTo(conversation);
            worker.execute(
                    accepted.path("answerMessageId")
                            .asLong()
            );
            // then
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "event:delta",
                    "event:completed",
                    "event:excluded_documents",
                    "\"count\":0"
            );
            try (InputStream replay = connect(
                    body(
                            request,
                            "후속 😀"
                    )
            ).body(); BufferedReader repeated = reader(replay)) {
                assertThat(accepted(repeated)).isEqualTo(accepted);
                assertThat(
                        repeated.lines()
                                .collect(Collectors.joining("\n"))
                ).contains("event:completed");
            }
        }
        assertThat(countMessages()).isEqualTo(4);
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM search_answer_executions")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("후속 질문의 범위 밖 판정은 문서 조회 없이 일반 답변으로 완료한다")
    void ask_success_outOfScopeGuidance() throws Exception {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.OUT_OF_SCOPE);
        try (InputStream stream = connect(
                body(
                        UUID.randomUUID(),
                        "케이크 만들어줘"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            JsonNode receipt = accepted(reader);
            worker.execute(
                    receipt.path("answerMessageId")
                            .asLong()
            );
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "event:completed",
                    "일반 대화 답변",
                    "\"items\":[]"
            )
                    .doesNotContain(
                            "event:failed",
                            "MEETING_SEARCH",
                            "OUT_OF_SCOPE"
                    );
        }
        verifyNoInteractions(retriever);
        verify(generator).generate(
                any(),
                any(),
                eq(List.of()),
                any()
        );
        assertThat(countMessages()).isEqualTo(4);
    }

    @Test
    @DisplayName("중지 뒤 후속 질문은 DRAFT 근거로 완료하고 늦은 작업과 녹음은 영향을 주지 않는다")
    void ask_success_stopThenFollowupAndRecording() throws Exception {
        // given
        long recording = fixtures.saveRecording(
                workspace,
                member,
                60000
        );
        jdbc.sql(
                "UPDATE recording_sessions SET status = 'RECORDING', ended_at = NULL, current_interval_started_at = started_at WHERE id = :id"
        )
                .param(
                        "id",
                        recording
                )
                .update();
        Map<String, Object> recordingBefore = jdbc.sql("SELECT * FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        recording
                )
                .query()
                .singleRow();
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
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
                        any(SearchToolScope.class),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        docs,
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("새 답변 😀");
            return List.of(document);
        }).when(generator)
                .generate(
                        any(SearchToolScope.class),
                        any(),
                        any(),
                        any()
                );
        // when
        long oldAnswer;
        try (InputStream stream = connect(
                body(
                        UUID.randomUUID(),
                        "중지할 질문"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            oldAnswer = accepted(reader).path("answerMessageId")
                    .asLong();
            jdbc.sql("UPDATE search_messages SET content = '부분 답변' WHERE id = :id")
                    .param(
                            "id",
                            oldAnswer
                    )
                    .update();
            HttpResponse<String> stop = client.send(
                    HttpRequest.newBuilder(
                            post(
                                    base() + "/answers/" + oldAnswer + "/stop",
                                    "",
                                    true,
                                    true
                            ),
                            (
                                    name,
                                    value
                            ) -> !name.equalsIgnoreCase("Accept")
                    )
                            .header(
                                    "Accept",
                                    "application/json"
                            )
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(stop.statusCode()).isEqualTo(200);
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:stopped");
        }
        try (InputStream stream = connect(
                body(
                        UUID.randomUUID(),
                        "그 결정은 왜 바뀌었어?"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            long answer = accepted(reader).path("answerMessageId")
                    .asLong();
            worker.execute(oldAnswer);
            worker.execute(answer);
            // then
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "새 답변 😀",
                    "event:evidence",
                    "event:completed"
            );
            HttpResponse<String> reconnect = client.send(
                    HttpRequest.newBuilder(uri(base() + "/answers/" + answer + "/events"))
                            .header(
                                    "Cookie",
                                    cookies
                            )
                            .header(
                                    "Accept",
                                    "text/event-stream"
                            )
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(reconnect.body()).contains(
                    "event:snapshot",
                    "새 답변 😀",
                    "event:completed"
            );
        }
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                oldAnswer
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("부분 답변");
        assertThat(
                jdbc.sql("SELECT * FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                recording
                        )
                        .query()
                        .singleRow()
        ).isEqualTo(recordingBefore);
        assertThat(countMessages()).isEqualTo(6);
    }

    @Test
    @DisplayName("연결을 닫아도 후속 답변은 생성되고 오류 시 안내 응답과 목록 노출을 보존한다")
    void ask_success_disconnectAndFailedPersistence() throws Exception {
        // given
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
                .param(
                        "id",
                        document
                )
                .update();
        when(
                retriever.retrieve(
                        any(SearchToolScope.class),
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
            delta.accept("실패 전 부분");
            throw new IllegalStateException("생성 실패");
        }).when(generator)
                .generate(
                        any(SearchToolScope.class),
                        any(),
                        any(),
                        any()
                );
        UUID request = UUID.randomUUID();
        long answer;
        // when
        try (InputStream stream = connect(
                body(
                        request,
                        "후속"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            answer = accepted(reader).path("answerMessageId")
                    .asLong();
        }
        worker.execute(answer);
        // then
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answer
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("COMPLETED");
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answer
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations WHERE id = :id")
                        .param(
                                "id",
                                conversation
                        )
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        try (InputStream replay = connect(
                body(
                        request,
                        "후속"
                )
        ).body(); BufferedReader reader = reader(replay)) {
            assertThat(
                    accepted(reader).path("answerMessageId")
                            .asLong()
            ).isEqualTo(answer);
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    SearchAnswerGuidance.UNAVAILABLE,
                    "event:completed"
            );
        }
        assertThat(countMessages()).isEqualTo(4);
    }

    @Test
    @DisplayName("진행 중 새 요청은 409이며 같은 키는 기존 ID·다른 본문은 충돌이다")
    void ask_failure_inProgressAndConflict() throws Exception {
        // given
        UUID request = UUID.randomUUID();
        long answerId;
        try (InputStream first = connect(
                body(
                        request,
                        "후속"
                )
        ).body(); BufferedReader reader = reader(first)) {
            JsonNode ids = accepted(reader);
            answerId = ids.path("answerMessageId")
                    .asLong();
            // when & then
            assertError(
                    post(
                            path(),
                            body(
                                    UUID.randomUUID(),
                                    "후속"
                            ),
                            true,
                            true
                    ),
                    409,
                    "ANSWER_ALREADY_IN_PROGRESS"
            );
            assertError(
                    post(
                            path(),
                            body(
                                    request,
                                    "다른 질문"
                            ),
                            true,
                            true
                    ),
                    409,
                    "REQUEST_ID_CONFLICT"
            );
            try (InputStream replay = connect(
                    body(
                            request,
                            "후속"
                    )
            ).body(); BufferedReader repeated = reader(replay)) {
                assertThat(accepted(repeated)).isEqualTo(ids);
            }
        }
        assertThat(countMessages()).isEqualTo(4);
        completeAnswerForCleanup(answerId);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "", "\u2003"})
    @DisplayName("빈 후속 질문은 접수 전 400으로 거절한다")
    void ask_failure_blank(String question) throws Exception {
        // when & then
        assertError(
                post(
                        path(),
                        body(
                                UUID.randomUUID(),
                                question
                        ),
                        true,
                        true
                ),
                400,
                "VALIDATION_ERROR"
        );
        assertThat(countMessages()).isEqualTo(2);
    }

    @Test
    @DisplayName("UUID·길이·JSON·경로 오류에서 신규 메시지는 없다")
    void ask_failure_invalidInput() throws Exception {
        // when & then
        assertError(
                post(
                        path(),
                        "{\"content\":\"질문\",\"requestId\":\"invalid\"}",
                        true,
                        true
                ),
                400,
                "VALIDATION_ERROR"
        );
        assertError(
                post(
                        path(),
                        body(
                                UUID.randomUUID(),
                                "😀".repeat(2001)
                        ),
                        true,
                        true
                ),
                400,
                "VALIDATION_ERROR"
        );
        assertError(
                post(
                        path(),
                        "{\"content\":123}",
                        true,
                        true
                ),
                400,
                "INVALID_REQUEST_BODY"
        );
        assertError(
                post(
                        path(),
                        "{",
                        true,
                        true
                ),
                400,
                "INVALID_REQUEST_BODY"
        );
        assertError(
                post(
                        target(0) + "/questions",
                        body(
                                UUID.randomUUID(),
                                "질문"
                        ),
                        true,
                        true
                ),
                400,
                "INVALID_PARAMETER"
        );
        assertThat(countMessages()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    @DisplayName("2,000 이모지 질문을 원문 그대로 접수한다")
    void ask_success_unicodeBoundary(String letter) throws Exception {
        // when
        long answerId;
        try (InputStream stream = connect(
                body(
                        UUID.randomUUID(),
                        letter.repeat(2000)
                )
        ).body(); BufferedReader reader = reader(stream)) {
            JsonNode ids = accepted(reader);
            answerId = ids.path("answerMessageId")
                    .asLong();
            // then
            assertThat(
                    jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                            .param(
                                    "id",
                                    ids.path("questionMessageId")
                                            .asLong()
                            )
                            .query(String.class)
                            .single()
            ).isEqualTo(letter.repeat(2000));
        }
        completeAnswerForCleanup(answerId);
    }

    @Test
    @DisplayName("인증·CSRF·소유권·현재 멤버십·없는 대화 오류를 구분한다")
    void ask_failure_securityAndScope() throws Exception {
        // given
        String body = body(
                UUID.randomUUID(),
                "질문"
        );
        // when & then
        assertError(
                post(
                        path(),
                        body,
                        false,
                        true
                ),
                401,
                "UNAUTHENTICATED"
        );
        assertError(
                post(
                        path(),
                        body,
                        true,
                        false
                ),
                403,
                "FORBIDDEN"
        );
        assertError(
                post(
                        target(99999) + "/questions",
                        body,
                        true,
                        true
                ),
                404,
                "CONVERSATION_NOT_FOUND"
        );
        long other = fixtures.saveMember("다른 멤버");
        fixtures.join(
                workspace,
                other
        );
        jdbc.sql("UPDATE search_conversations SET member_id = :id")
                .param(
                        "id",
                        other
                )
                .update();
        assertError(
                post(
                        path(),
                        body,
                        true,
                        true
                ),
                403,
                "SEARCH_ACCESS_DENIED"
        );
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        assertError(
                post(
                        path(),
                        body,
                        true,
                        true
                ),
                403,
                "SEARCH_ACCESS_DENIED"
        );
        assertThat(countMessages()).isEqualTo(2);
    }

    private HttpResponse<InputStream> connect(String body) throws Exception {
        HttpResponse<InputStream> response = client.send(
                post(
                        path(),
                        body,
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
        assertThat(
                response.headers()
                        .firstValue("Cache-Control")
                        .orElseThrow()
        ).isEqualTo("no-cache");
        return response;
    }

    @Test
    @DisplayName("가용성 거절은 접수 전 JSON 오류이며 SSE 예약을 반환한다")
    void ask_failure_unavailableReleasesReservation() throws Exception {
        // given
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        // when & then
        for (int rejected = 0; rejected <= streamLimits.getStreamConcurrency(); rejected++) {
            assertError(
                    post(
                            path(),
                            body(
                                    UUID.randomUUID(),
                                    "질문"
                            ),
                            true,
                            true
                    ),
                    500,
                    "ANSWER_GENERATION_UNAVAILABLE"
            );
        }
        assertThat(countMessages()).isEqualTo(2);
        doNothing().when(availability)
                .requireAvailable();
        long answerId;
        try (InputStream stream = connect(
                body(
                        UUID.randomUUID(),
                        "재접수"
                )
        ).body(); BufferedReader reader = reader(stream)) {
            JsonNode receipt = accepted(reader);
            answerId = receipt.path("answerMessageId")
                    .asLong();
            assertThat(
                    receipt.path("conversationId")
                            .asLong()
            ).isEqualTo(conversation);
        }
        assertThat(countMessages()).isEqualTo(4);
        completeAnswerForCleanup(answerId);
    }

    private void completeAnswerForCleanup(long answerId) {
        worker.execute(answerId);
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("COMPLETED");
    }

    private void assertError(
            HttpRequest request,
            int status,
            String code
    ) throws Exception {
        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains(code);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
        ).contains("application/json");
    }

    private HttpRequest post(
            String path,
            String body,
            boolean authenticated,
            boolean csrf
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "text/event-stream"
                );
        String cookie = csrfCookie;
        if (authenticated) {
            cookie = cookies;
        }
        builder.header(
                "Cookie",
                cookie
        );
        if (csrf) {
            builder.header(
                    "X-XSRF-TOKEN",
                    csrfToken
            );
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String body(
            UUID request,
            String content
    ) {
        return json.writeValueAsString(
                Map.of(
                        "content",
                        content,
                        "requestId",
                        request.toString()
                )
        );
    }

    private String base() {
        return target(conversation);
    }

    private String target(long id) {
        return "/api/v1/workspaces/" + workspace + "/search/conversations/" + id;
    }

    private String path() {
        return base() + "/questions";
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private BufferedReader reader(InputStream stream) {
        return new BufferedReader(
                new InputStreamReader(
                        stream,
                        StandardCharsets.UTF_8
                )
        );
    }

    private JsonNode accepted(BufferedReader reader) throws Exception {
        assertThat(reader.readLine()).isEqualTo("event:accepted");
        String data = reader.readLine();
        assertThat(data).startsWith("data:");
        assertThat(reader.readLine()).isEmpty();
        return json.readTree(data.substring(5));
    }

    private long countMessages() {
        return jdbc.sql("SELECT COUNT(*) FROM search_messages")
                .query(Long.class)
                .single();
    }
}
