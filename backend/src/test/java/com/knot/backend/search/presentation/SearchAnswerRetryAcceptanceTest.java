package com.knot.backend.search.presentation;

import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.application.SearchIntentClassifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.SearchAnswerExecutionService;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
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
import java.util.stream.Collectors;
import java.util.function.Consumer;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
class SearchAnswerRetryAcceptanceTest {

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
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private SearchWorkerProperties limits;
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
    private SearchQuestionAcceptedResult first;
    private String csrf;
    private String csrfCookie;
    private String cookies;

    @BeforeEach
    void setUp() throws Exception {
        awaitStreamsIdle();
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        member = fixtures.saveMember("재시도 질문자");
        workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        first = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "원래 질문",
                        UUID.randomUUID()
                )
        );
        failCurrent();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        csrf = json.readTree(response.body())
                .path("token")
                .asString();
        csrfCookie = response.headers()
                .allValues("Set-Cookie")
                .stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow()
                .split(";")[0];
        cookies = csrfCookie + "; " + jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        member,
                        "재시도 질문자",
                        null
                )
        );
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
    }

    @Test
    @DisplayName("기존 ID로 accepted를 먼저 보내고 완료 답변은 같은 requestId로 복구한다")
    void retry_success_acceptedGenerationAndReplay() throws Exception {
        // given
        String body = body(UUID.randomUUID());
        // when
        try (InputStream stream = connect(body).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            worker.execute(first.answerMessageId());
            String rest = reader.lines()
                    .collect(Collectors.joining("\n"));
            // then
            assertThat(rest).contains(
                    "event:delta",
                    "event:completed",
                    "event:excluded_documents",
                    "현재 검색 가능한 회의 문서"
            )
                    .doesNotContain(
                            "실패한 부분",
                            "receiptId"
                    );
        }
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        try (InputStream stream = connect(body).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:completed")
                    .doesNotContain("실패한 부분");
        }
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
    }

    @AfterEach
    void finishAcceptedWork() throws InterruptedException {
        try {
            jdbc.sql("SELECT id FROM search_messages WHERE status = 'STREAMING'")
                    .query(Long.class)
                    .list()
                    .forEach(worker::execute);
        } finally {
            awaitStreamsIdle();
        }
    }

    private void awaitStreamsIdle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5)
                .toNanos();
        while ((streamExecutor.getActiveCount() != 0 || streamExecutor.getQueueSize() != 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(streamExecutor.getActiveCount()).as("다음 TRUNCATE 전에 이전 SSE 작업이 종료되어야 한다")
                .isZero();
        assertThat(streamExecutor.getQueueSize()).isZero();
    }

    @Test
    @DisplayName("재시도도 의도를 다시 확인하고 고정 안내로 기존 답변을 완료한다")
    void retry_success_outOfScopeGuidance() throws Exception {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.OUT_OF_SCOPE);
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            worker.execute(first.answerMessageId());
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "event:completed",
                    "Knot은 회의 기록의 조회·요약·비교",
                    "\"items\":[]"
            )
                    .doesNotContain(
                            "event:failed",
                            "실패한 부분",
                            "OUT_OF_SCOPE"
                    );
        }
        verifyNoInteractions(
                retriever,
                generator
        );
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE role = 'ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("COMPLETED");
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
    }

    @Test
    @DisplayName("화면 연결이 끊겨도 재시도 작업은 남으며 이전 UUID는 새 시도 출력을 받지 않는다")
    void retry_success_disconnectAndOldReplayIsolation() throws Exception {
        // given
        String oldRequest = body(UUID.randomUUID());
        try (InputStream stream = connect(oldRequest).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
        failCurrent();
        // when
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            worker.execute(first.answerMessageId());
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:completed");
        }
        try (InputStream stream = connect(oldRequest).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            // then
            assertThat(reader.readLine()).isNull();
        }
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"requestId\":null}", "{\"requestId\":\"bad\"}", "{\"requestId\":\"1-1-1-1-1\"}",
            "{\"requestId\":12}", "{\"requestId\":[]}"})
    @DisplayName("누락·잘못된 UUID·문자열 아닌 값은 접수 전에 JSON 오류로 거절한다")
    void retry_failure_invalidBody(String body) throws Exception {
        // when & then
        HttpResponse<String> response = send(
                path(first.questionMessageId()),
                body,
                true,
                true
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
        ).contains("application/json");
        assertThat(count("search_request_receipts")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT status FROM search_messages WHERE role = 'ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("인증·CSRF·소유권·현재 멤버십은 재시도 접수 전에 검증한다")
    void retry_failure_access() throws Exception {
        // given
        String request = body(UUID.randomUUID());
        // when & then
        assertError(
                send(
                        path(first.questionMessageId()),
                        request,
                        false,
                        true
                ),
                401,
                "UNAUTHENTICATED"
        );
        assertError(
                send(
                        path(first.questionMessageId()),
                        request,
                        true,
                        false
                ),
                403,
                "FORBIDDEN"
        );
        long other = fixtures.saveMember("다른 소유자");
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
                send(
                        path(first.questionMessageId()),
                        request,
                        true,
                        true
                ),
                403,
                "SEARCH_ACCESS_DENIED"
        );
        jdbc.sql("UPDATE search_conversations SET member_id = :id")
                .param(
                        "id",
                        member
                )
                .update();
        fixtures.leave(
                workspace,
                member
        );
        assertError(
                send(
                        path(first.questionMessageId()),
                        request,
                        true,
                        true
                ),
                403,
                "SEARCH_ACCESS_DENIED"
        );
        assertThat(count("search_request_receipts")).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 질문·진행 중 답변·다른 대상 UUID 재사용을 구분한다")
    void retry_failure_questionAndConflict() throws Exception {
        // given
        String request = body(UUID.randomUUID());
        // when & then
        assertError(
                send(
                        path(99999),
                        request,
                        true,
                        true
                ),
                404,
                "QUESTION_NOT_FOUND"
        );
        assertError(
                send(
                        path(first.answerMessageId()),
                        request,
                        true,
                        true
                ),
                404,
                "QUESTION_NOT_FOUND"
        );
        try (InputStream stream = connect(request).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
        assertError(
                send(
                        path(first.questionMessageId()),
                        body(UUID.randomUUID()),
                        true,
                        true
                ),
                409,
                "ANSWER_ALREADY_IN_PROGRESS"
        );
        assertError(
                send(
                        path(99999),
                        request,
                        true,
                        true
                ),
                409,
                "REQUEST_ID_CONFLICT"
        );
        worker.execute(first.answerMessageId());
        assertError(
                send(
                        path(first.questionMessageId()),
                        body(UUID.randomUUID()),
                        true,
                        true
                ),
                409,
                "ANSWER_NOT_FAILED"
        );
        assertThat(count("search_messages")).isEqualTo(2);
    }

    @Test
    @DisplayName("생성 불가 오류는 FAILED 본문을 유지하고 SSE 예약을 해제한다")
    void retry_failure_unavailableBeforeReset() throws Exception {
        // given
        doThrow(new SearchException(SearchErrorCode.ANSWER_GENERATION_UNAVAILABLE)).when(availability)
                .requireAvailable();
        // when & then
        for (int rejected = 0; rejected <= limits.getStreamConcurrency(); rejected++) {
            assertError(
                    send(
                            path(first.questionMessageId()),
                            body(UUID.randomUUID()),
                            true,
                            true
                    ),
                    500,
                    "ANSWER_GENERATION_UNAVAILABLE"
            );
        }
        assertThat(count("search_request_receipts")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE role = 'ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("실패한 부분");
        doNothing().when(availability)
                .requireAvailable();
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
    }

    private void failCurrent() {
        SearchAnswerWorkResult work = executions.claim(first.answerMessageId())
                .orElseThrow();
        executions.append(
                work.answerId(),
                work.token(),
                "실패한 부분"
        );
        executions.fail(
                work.answerId(),
                work.token(),
                "ANSWER_GENERATION_FAILED"
        );
    }

    @Test
    @DisplayName("접수 후 생성 실패는 부분 본문을 보존하고 같은 질문을 다시 재시도할 수 있다")
    void retry_success_generationFailurePreservesPartial() throws Exception {
        // given
        long recording = fixtures.saveRecording(
                workspace,
                member,
                1000
        );
        long transcript = fixtures.saveTranscript(recording);
        long job = fixtures.saveJob(
                transcript,
                "SUCCEEDED"
        );
        long document = fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                job,
                "실제 근거"
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
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
                                        "회의",
                                        "주제",
                                        "문서 본문"
                                )
                        ),
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("새 시도 부분 답변");
            throw new IllegalStateException("generation failed");
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );
        // when
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
            worker.execute(first.answerMessageId());
            // then
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    "새 시도 부분 답변",
                    "event:failed",
                    "ANSWER_GENERATION_FAILED"
            )
                    .doesNotContain("실패한 부분");
        }
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations")
                        .query(Boolean.class)
                        .single()
        ).isFalse();
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE role = 'ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo("새 시도 부분 답변");
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(3);
    }

    @Test
    @DisplayName("접속 종료 뒤 worker 완료 결과를 GET snapshot과 종료 이벤트로 복구한다")
    void retry_success_workerAfterDisconnectAndSnapshot() throws Exception {
        // given
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
        // when
        worker.execute(first.answerMessageId());
        String path = "/api/v1/workspaces/" + workspace + "/search/conversations/" + first.conversationId()
                + "/answers/" + first.answerMessageId() + "/events";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(15))
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
        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "event:snapshot",
                "현재 검색 가능한 회의 문서",
                "event:completed"
        )
                .doesNotContain(
                        "실패한 부분",
                        "receiptId",
                        "excluded_documents"
                );
        assertThat(count("search_messages")).isEqualTo(2);
    }

    @Test
    @DisplayName("실패 답변 재시도는 DRAFT 근거로 완료하고 재연결에서 근거를 복구한다")
    void retry_success_draftEvidenceAndReconnect() throws Exception {
        // given
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
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
                                        "초안",
                                        "주제",
                                        "본문"
                                )
                        ),
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("DRAFT 기반 답변");
            return List.of(document);
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );
        // when
        try (InputStream stream = connect(body(UUID.randomUUID())).body(); BufferedReader reader = reader(stream)) {
            assertAccepted(accepted(reader));
        }
        worker.execute(first.answerMessageId());
        String path = "/api/v1/workspaces/" + workspace + "/search/conversations/" + first.conversationId()
                + "/answers/" + first.answerMessageId() + "/events";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri(path))
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
        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "event:snapshot",
                "DRAFT 기반 답변",
                "event:evidence",
                "\"documentId\":" + document,
                "event:completed"
        );
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                first.answerMessageId()
                        )
                        .query(Long.class)
                        .list()
        ).containsExactly(document);
        assertThat(
                jdbc.sql("SELECT status FROM documents WHERE id = :id")
                        .param(
                                "id",
                                document
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("DRAFT");
        assertThat(count("search_messages")).isEqualTo(2);
    }

    private HttpResponse<InputStream> connect(String body) throws Exception {
        HttpResponse<InputStream> response = client.send(
                post(
                        path(first.questionMessageId()),
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
        return response;
    }

    private HttpResponse<String> send(
            String path,
            String body,
            boolean auth,
            boolean csrfHeader
    ) throws Exception {
        return client.send(
                post(
                        path,
                        body,
                        auth,
                        csrfHeader
                ),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest post(
            String path,
            String body,
            boolean auth,
            boolean csrfHeader
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
        if (auth) {
            cookie = cookies;
        }
        builder.header(
                "Cookie",
                cookie
        );
        if (csrfHeader) {
            builder.header(
                    "X-XSRF-TOKEN",
                    csrf
            );
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String body(UUID request) {
        return "{\"requestId\":\"" + request + "\"}";
    }

    private String path(long question) {
        return "/api/v1/workspaces/" + workspace + "/search/conversations/" + first.conversationId() + "/questions/"
                + question + "/retry";
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
        String line = reader.readLine();
        assertThat(line).startsWith("data:");
        assertThat(reader.readLine()).isEmpty();
        return json.readTree(line.substring(5));
    }

    private void assertAccepted(JsonNode data) {
        assertThat(
                data.path("questionId")
                        .asLong()
        ).isEqualTo(first.questionMessageId());
        assertThat(
                data.path("answerMessageId")
                        .asLong()
        ).isEqualTo(first.answerMessageId());
        assertThat(
                data.path("status")
                        .asString()
        ).isEqualTo("STREAMING");
        assertThat(data.has("receiptId")).isFalse();
        assertThat(data.has("questionMessageId")).isFalse();
    }

    private void assertError(
            HttpResponse<String> response,
            int status,
            String code
    ) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains(code);
        assertThat(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
        ).contains("application/json");
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
