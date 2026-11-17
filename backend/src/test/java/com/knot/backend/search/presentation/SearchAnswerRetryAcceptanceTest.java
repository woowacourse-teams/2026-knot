package com.knot.backend.search.presentation;

import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.application.SearchIntentClassifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import com.knot.backend.search.application.SearchAnswerGuidance;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchWorkerProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.SearchAnswerExecutionService;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchGenerationAvailability;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.infrastructure.llm.SearchFastPathDocumentRetriever;
import com.knot.backend.search.infrastructure.llm.SearchQueryNormalizer;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(30)
class SearchAnswerRetryAcceptanceTest {
    private static final Instant FIRST_AT = Instant.parse("2026-11-16T14:59:50Z");
    private static final Instant RETRY_AT = Instant.parse("2026-11-16T15:00:10Z");
    private static final Instant WORK_AT = Instant.parse("2026-11-16T15:00:20Z");
    private static final Instant NOVEMBER_15_MEETING = Instant.parse("2026-11-15T03:00:00Z");
    private static final Instant NOVEMBER_16_MEETING = Instant.parse("2026-11-16T03:00:00Z");
    private static final String RELATIVE_QUESTION = "어제 회의 요약";

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
    private SearchDocumentQuery documentQuery;
    @Autowired
    private SearchToolAccess toolAccess;
    @MockitoSpyBean
    private Clock clock;
    @MockitoSpyBean
    private SearchAgentProperties agentProperties;
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
                    "일반 대화 답변"
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
    @DisplayName("재시도도 의도를 다시 확인하고 일반 답변으로 기존 답변을 완료한다")
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
                    "일반 대화 답변",
                    "\"items\":[]"
            )
                    .doesNotContain(
                            "event:failed",
                            "실패한 부분",
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
        // Historical FAILED fixture: new technical errors now store completed guidance
        // instead.
        jdbc.sql("UPDATE search_messages SET status='FAILED', failure_code='ANSWER_GENERATION_FAILED' WHERE id=:id")
                .param(
                        "id",
                        work.answerId()
                )
                .update();
        jdbc.sql("UPDATE search_answer_executions SET state='FINISHED' WHERE answer_id=:id")
                .param(
                        "id",
                        work.answerId()
                )
                .update();
        jdbc.sql("UPDATE search_conversations SET visible_in_list=false WHERE id=:id")
                .param(
                        "id",
                        work.conversationId()
                )
                .update();
    }

    @Test
    @DisplayName("이전 실패 재시도 중 기술 오류도 미검증 본문 대신 안내로 완료한다")
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
                    SearchAnswerGuidance.UNAVAILABLE,
                    "event:completed"
            )
                    .doesNotContain(
                            "실패한 부분",
                            "새 시도 부분 답변",
                            "event:failed"
                    );
        }
        assertThat(
                jdbc.sql("SELECT visible_in_list FROM search_conversations")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE role = 'ASSISTANT'")
                        .query(String.class)
                        .single()
        ).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        HttpResponse<String> rejected = send(
                path(first.questionMessageId()),
                body(UUID.randomUUID()),
                true,
                true
        );
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(rejected.body()).contains("ANSWER_NOT_FAILED");
        assertThat(count("search_messages")).isEqualTo(2);
        assertThat(count("search_request_receipts")).isEqualTo(2);
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
                "일반 대화 답변",
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

    @Test
    @DisplayName("자정 뒤 재시도에서 최초 날짜와 재시도 접수까지의 문서를 사용한다")
    void retry_success_keepsOriginalDateAndUsesRetryCutoff() throws Exception {
        prepareMidnightSearch();
        long original = timedDocument(
                NOVEMBER_15_MEETING,
                FIRST_AT.minusSeconds(1)
        );
        long beforeRetry = timedDocument(
                NOVEMBER_15_MEETING,
                RETRY_AT.minusSeconds(5)
        );
        long atRetry = timedDocument(
                NOVEMBER_15_MEETING,
                RETRY_AT
        );
        timedDocument(
                NOVEMBER_15_MEETING,
                RETRY_AT.plusSeconds(5)
        );
        timedDocument(
                NOVEMBER_16_MEETING,
                FIRST_AT.minusSeconds(1)
        );

        try (BufferedReader reader = openRelativeQuestion()) {
            first = acceptFirstQuestion(reader);
            failCurrent();
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:failed");
        }
        setTime(RETRY_AT);
        String request = body(UUID.randomUUID());
        long started = System.nanoTime();
        try (BufferedReader reader = reader(connect(request).body())) {
            assertAccepted(accepted(reader));
            setTime(WORK_AT);
            assertThat(
                    executeAndMeasure(
                            first.answerMessageId(),
                            reader,
                            started,
                            "retry"
                    )
            ).containsExactlyInAnyOrder(
                    original,
                    beforeRetry,
                    atRetry
            );
        }
        long receipts = count("search_request_receipts");
        setTime(WORK_AT.plusSeconds(5));
        HttpResponse<String> replay = send(
                path(first.questionMessageId()),
                request,
                true,
                true
        );
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains(
                "event:completed",
                "날짜 기준 검증 답변"
        );
        assertThat(count("search_request_receipts")).isEqualTo(receipts);
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                first.answerMessageId()
                        )
                        .query(Long.class)
                        .list()
        ).containsExactlyInAnyOrder(
                original,
                beforeRetry,
                atRetry
        );
    }

    @Test
    @DisplayName("같은 날짜에서 재시도 접수 후 워커보다 먼저 생성된 문서는 제외한다")
    void retry_success_excludesDocumentsCreatedWhileRetryQueued() throws Exception {
        prepareMidnightSearch();
        long original = timedDocument(
                NOVEMBER_15_MEETING,
                FIRST_AT.minusSeconds(1)
        );
        timedDocument(
                NOVEMBER_15_MEETING,
                FIRST_AT.plusSeconds(6)
        );
        try (BufferedReader reader = openRelativeQuestion()) {
            first = acceptFirstQuestion(reader);
            failCurrent();
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:failed");
        }
        setTime(FIRST_AT.plusSeconds(4));
        long started = System.nanoTime();
        try (BufferedReader reader = reader(connect(body(UUID.randomUUID())).body())) {
            assertAccepted(accepted(reader));
            setTime(FIRST_AT.plusSeconds(8));
            assertThat(
                    executeAndMeasure(
                            first.answerMessageId(),
                            reader,
                            started,
                            "retry-same-day"
                    )
            ).containsExactly(original);
        }
    }

    @Test
    @DisplayName("자정을 지나 워커가 실행돼도 최초 접수 기준을 사용하고 새 질문은 새 날짜를 사용한다")
    void create_success_usesAcceptanceTimeAcrossMidnightQueue() throws Exception {
        prepareMidnightSearch();
        long original = timedDocument(
                NOVEMBER_15_MEETING,
                FIRST_AT.minusSeconds(1)
        );
        timedDocument(
                NOVEMBER_15_MEETING,
                FIRST_AT.plusSeconds(2)
        );
        long nextDay = timedDocument(
                NOVEMBER_16_MEETING,
                FIRST_AT.minusSeconds(1)
        );
        long originalStarted = System.nanoTime();
        try (BufferedReader reader = openRelativeQuestion()) {
            SearchQuestionAcceptedResult accepted = acceptFirstQuestion(reader);
            setTime(WORK_AT);
            assertThat(
                    executeAndMeasure(
                            accepted.answerMessageId(),
                            reader,
                            originalStarted,
                            "first-after-queue"
                    )
            ).containsExactly(original);
        }
        long nextStarted = System.nanoTime();
        try (BufferedReader reader = openRelativeQuestion()) {
            SearchQuestionAcceptedResult accepted = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            accepted.answerMessageId(),
                            reader,
                            nextStarted,
                            "new-question"
                    )
            ).containsExactly(nextDay);
        }
    }

    @Test
    @DisplayName("월요일 경계를 넘긴 새 후속 질문과 기존 질문 재시도는 각각 해당 주를 검색한다")
    void retry_success_keepsWeekAcrossSeoulMonday() throws Exception {
        Instant sunday = Instant.parse("2026-11-15T14:59:50Z");
        Instant monday = sunday.plusSeconds(20);
        String question = "지난주 회의";
        prepareMidnightSearch(sunday);
        long previousWeek = timedDocument(
                Instant.parse("2026-11-05T03:00:00Z"),
                sunday.minusSeconds(1)
        );
        long newWeek = timedDocument(
                Instant.parse("2026-11-12T03:00:00Z"),
                sunday.minusSeconds(1)
        );
        long beforeRetry = timedDocument(
                Instant.parse("2026-11-06T03:00:00Z"),
                monday.minusSeconds(5)
        );
        timedDocument(
                Instant.parse("2026-11-06T03:00:00Z"),
                monday.plusSeconds(5)
        );

        SearchQuestionAcceptedResult original;
        long started = System.nanoTime();
        try (BufferedReader reader = openQuestion(
                conversationsPath(),
                question
        )) {
            original = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            original.answerMessageId(),
                            reader,
                            started,
                            "week-first"
                    )
            ).containsExactly(previousWeek);
        }
        try (BufferedReader reader = openQuestion(
                conversationsPath(),
                question
        )) {
            first = acceptFirstQuestion(reader);
            failCurrent();
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains("event:failed");
        }

        setTime(monday);
        started = System.nanoTime();
        try (BufferedReader reader = openQuestion(
                conversationsPath() + "/" + original.conversationId() + "/questions",
                question
        )) {
            SearchQuestionAcceptedResult followup = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            followup.answerMessageId(),
                            reader,
                            started,
                            "week-followup"
                    )
            ).containsExactly(newWeek);
        }
        String request = body(UUID.randomUUID());
        started = System.nanoTime();
        try (BufferedReader reader = reader(connect(request).body())) {
            assertAccepted(accepted(reader));
            setTime(monday.plusSeconds(10));
            assertThat(
                    executeAndMeasure(
                            first.answerMessageId(),
                            reader,
                            started,
                            "week-retry"
                    )
            ).containsExactlyInAnyOrder(
                    previousWeek,
                    beforeRetry
            );
        }
        long receipts = count("search_request_receipts");
        HttpResponse<String> replay = send(
                path(first.questionMessageId()),
                request,
                true,
                true
        );
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("event:completed");
        assertThat(count("search_request_receipts")).isEqualTo(receipts);
    }

    @Test
    @DisplayName("후속 질문이 하루를 이어받아 정각을 제외하고 한쪽 날짜 경계도 저장한다")
    void followup_success_inheritsCalendarDayAndPersistsOpenDateBoundary() throws Exception {
        prepareMidnightSearch();
        doReturn(true).when(agentProperties)
                .isFollowupContextEnabled();
        Instant boundary = Instant.parse("2026-11-15T06:00:00Z");
        long before = timedDocument(
                boundary.minusNanos(1_000),
                FIRST_AT.minusSeconds(1)
        );
        long exact = timedDocument(
                boundary,
                FIRST_AT.minusSeconds(1)
        );
        long after = timedDocument(
                boundary.plusNanos(1_000),
                FIRST_AT.minusSeconds(1)
        );
        long priorDay = timedDocument(
                Instant.parse("2026-11-14T03:00:00Z"),
                FIRST_AT.minusSeconds(1)
        );

        SearchQuestionAcceptedResult original;
        long started = System.nanoTime();
        try (BufferedReader reader = openQuestion(
                conversationsPath(),
                "11월 15일에 열린 회의"
        )) {
            original = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            original.answerMessageId(),
                            reader,
                            started,
                            "day-first"
                    )
            ).containsExactlyInAnyOrder(
                    before,
                    exact,
                    after
            );
        }
        String followupPath = conversationsPath() + "/" + original.conversationId() + "/questions";
        started = System.nanoTime();
        try (BufferedReader reader = openQuestion(
                followupPath,
                "오후 3시 이전에 시작한 회의만"
        )) {
            SearchQuestionAcceptedResult followup = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            followup.answerMessageId(),
                            reader,
                            started,
                            "time-followup"
                    )
            ).containsExactly(before);
        }
        started = System.nanoTime();
        try (BufferedReader reader = openQuestion(
                followupPath,
                "11월 15일 이전에 열린 회의"
        )) {
            SearchQuestionAcceptedResult earlier = acceptFirstQuestion(reader);
            assertThat(
                    executeAndMeasure(
                            earlier.answerMessageId(),
                            reader,
                            started,
                            "open-period"
                    )
            ).containsExactly(priorDay);
            assertThat(
                    jdbc.sql("""
                            SELECT recorded_from IS NULL AND recorded_before = :end_at
                            FROM search_question_contexts WHERE question_message_id = :id
                            """)
                            .param(
                                    "end_at",
                                    Timestamp.from(Instant.parse("2026-11-14T15:00:00Z"))
                            )
                            .param(
                                    "id",
                                    earlier.questionMessageId()
                            )
                            .query(Boolean.class)
                            .single()
            ).isTrue();
        }
    }

    @Test
    @DisplayName("존재하지 않는 날짜는 검색이나 생성 없이 SSE에서 확인한다")
    void create_success_clarifiesInvalidDateWithoutSearching() throws Exception {
        prepareMidnightSearch();

        try (BufferedReader reader = openQuestion(
                conversationsPath(),
                "2026년 2월 29일에 열린 회의"
        )) {
            SearchQuestionAcceptedResult question = acceptFirstQuestion(reader);
            worker.execute(question.answerMessageId());
            assertThat(
                    reader.lines()
                            .collect(Collectors.joining("\n"))
            ).contains(
                    SearchAnswerGuidance.CLARIFY_MEETING_DATE,
                    "event:completed"
            )
                    .doesNotContain("event:failed");
        }
        org.mockito.Mockito.verifyNoInteractions(
                retriever,
                generator
        );
    }

    private void prepareMidnightSearch() {
        prepareMidnightSearch(FIRST_AT);
    }

    private void prepareMidnightSearch(Instant reference) {
        setTime(reference);
        cookies = csrfCookie + "; " + jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        member,
                        "재시도 질문자",
                        null
                )
        );
        SearchDocumentRetriever actual = new SearchFastPathDocumentRetriever(
                documentQuery,
                toolAccess,
                new SearchQueryNormalizer()
        );
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenAnswer(
                call -> actual.retrieve(
                        call.getArgument(0),
                        call.getArgument(1)
                )
        );
        doAnswer(call -> {
            List<SearchDocumentResult> found = call.getArgument(2);
            Consumer<String> delta = call.getArgument(3);
            delta.accept("날짜 기준 검증 답변");
            return found.stream()
                    .map(SearchDocumentResult::documentId)
                    .toList();
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    private void setTime(Instant time) {
        doReturn(time).when(clock)
                .instant();
    }

    private long timedDocument(
            Instant meetingTime,
            Instant createdAt
    ) {
        long documentId = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET created_at = :createdAt WHERE id = :id")
                .param(
                        "createdAt",
                        Timestamp.from(createdAt)
                )
                .param(
                        "id",
                        documentId
                )
                .update();
        jdbc.sql("""
                UPDATE recording_sessions
                SET started_at = :startedAt, ended_at = :endedAt, last_seen_at = :endedAt
                WHERE id = (SELECT recording_session_id FROM documents WHERE id = :id)
                """)
                .param(
                        "startedAt",
                        Timestamp.from(meetingTime)
                )
                .param(
                        "endedAt",
                        Timestamp.from(meetingTime.plusSeconds(60))
                )
                .param(
                        "id",
                        documentId
                )
                .update();
        return documentId;
    }

    private String conversationsPath() {
        return "/api/v1/workspaces/" + workspace + "/search/conversations";
    }

    private BufferedReader openRelativeQuestion() throws Exception {
        return openQuestion(
                conversationsPath(),
                RELATIVE_QUESTION
        );
    }

    private BufferedReader openQuestion(
            String path,
            String question
    ) throws Exception {
        String request = json.writeValueAsString(
                Map.of(
                        "content",
                        question,
                        "requestId",
                        UUID.randomUUID()
                )
        );
        HttpResponse<InputStream> response = client.send(
                post(
                        path,
                        request,
                        true,
                        true
                ),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return reader(response.body());
    }

    private SearchQuestionAcceptedResult acceptFirstQuestion(BufferedReader reader) throws Exception {
        JsonNode payload = accepted(reader);
        return new SearchQuestionAcceptedResult(
                payload.path("conversationId")
                        .asLong(),
                payload.path("questionMessageId")
                        .asLong(),
                payload.path("answerMessageId")
                        .asLong()
        );
    }

    private List<Long> executeAndMeasure(
            long answerId,
            BufferedReader reader,
            long start,
            String label
    ) throws Exception {
        CompletableFuture<Void> execution = CompletableFuture.runAsync(() -> worker.execute(answerId));
        Long firstDelta = null;
        List<Long> documentIds = new ArrayList<>();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if ("event:delta".equals(line)) {
                JsonNode data = json.readTree(
                        reader.readLine()
                                .substring(5)
                );
                if (!data.path("text")
                        .asString()
                        .isBlank() && firstDelta == null) {
                    firstDelta = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
            }
            if ("event:evidence".equals(line)) {
                JsonNode data = json.readTree(
                        reader.readLine()
                                .substring(5)
                );
                for (JsonNode item : data.path("items")) {
                    documentIds.add(
                            item.path("documentId")
                                    .asLong()
                    );
                }
            }
            if ("event:completed".equals(line)) {
                assertThat(firstDelta).isNotNull();
                System.out.printf(
                        "SEARCH_REQUEST_TIME %s firstDeltaMs=%d%n",
                        label,
                        firstDelta
                );
                execution.get(
                        5,
                        TimeUnit.SECONDS
                );
                return documentIds;
            }
            assertThat(line).isNotEqualTo("event:failed");
        }
        throw new AssertionError("SSE completed event was not received");
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
