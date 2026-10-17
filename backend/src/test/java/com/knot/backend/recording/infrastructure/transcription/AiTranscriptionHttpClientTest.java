package com.knot.backend.recording.infrastructure.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.global.config.TranscriptionConfig;
import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.LlmHttpServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiTranscriptionHttpClientTest {
    private static final String PATH = "/api/v1/transcription-jobs";
    private static final UUID ID = UUID.fromString("019bbbc0-51ae-7000-8000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("접수에 필요한 두 필드와 인증 헤더를 전송하며 HTTP 202와 작업 상태를 구분한다")
    void submit_success() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            AtomicReference<String> method = new AtomicReference<>();
            respond(
                    server,
                    202,
                    succeeded(),
                    method
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            AiTranscriptionJob job = client.submit(
                    ID,
                    "https://audio.example/private?signature=secret"
            );

            // then
            assertThat(job.status()).isEqualTo(AiTranscriptionJob.Status.SUCCEEDED);
            assertThat(
                    job.result()
                            .utterances()
            ).hasSize(1);
            assertThat(method.get()).isEqualTo("POST");
            assertThat(server.authorization()).isEqualTo("Bearer test-secret");
            JsonNode request = objectMapper.readTree(server.requestBody());
            assertThat(request.size()).isEqualTo(2);
            assertThat(
                    request.path("attemptId")
                            .asString()
            ).isEqualTo(ID.toString());
            assertThat(
                    request.path("downloadUrl")
                            .asString()
            ).isEqualTo("https://audio.example/private?signature=secret");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("조회는 해당 작업 ID의 GET 주소와 HTTP 200을 사용한다")
    void get_success() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            AtomicReference<String> method = new AtomicReference<>();
            AtomicReference<String> path = new AtomicReference<>();
            server.respond(
                    PATH,
                    exchange -> {
                        method.set(exchange.getRequestMethod());
                        path.set(
                                exchange.getRequestURI()
                                        .getPath()
                        );
                        byte[] bytes = succeeded().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(
                                200,
                                bytes.length
                        );
                        exchange.getResponseBody()
                                .write(bytes);
                    }
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            AiTranscriptionJob job = client.get(ID);

            // then
            assertThat(job.attemptId()).isEqualTo(ID);
            assertThat(
                    job.result()
                            .rawWhisperJson()
            ).isEqualTo("{\"transcription\":[]}");
            assertThat(method.get()).isEqualTo("GET");
            assertThat(path.get()).isEqualTo(PATH + "/" + ID);
            assertThat(server.authorization()).isEqualTo("Bearer test-secret");
        }
    }

    @Test
    @DisplayName("진행 중 작업은 결과 없이 반환한다")
    void get_success_processing() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    200,
                    jobBody(
                            "PROCESSING",
                            null
                    ),
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            AiTranscriptionJob job = client.get(ID);

            // then
            assertThat(job.status()).isEqualTo(AiTranscriptionJob.Status.PROCESSING);
            assertThat(job.result()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STT_FAILED", "INFERENCE_TIMEOUT", "FUTURE_RUNNER_FAILURE"})
    @DisplayName("실패 원인은 서버의 개별 오류 목록을 복제하지 않고 제한된 코드 형식으로 받는다")
    void get_success_failedJob(String failure) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    200,
                    jobBody(
                            "FAILED",
                            failure
                    ),
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            AiTranscriptionJob job = client.get(ID);

            // then
            assertThat(job.status()).isEqualTo(AiTranscriptionJob.Status.FAILED);
            assertThat(job.failureCode()).isEqualTo(failure);
            assertThat(job.result()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"401, AI_AUTHENTICATION_FAILED", "403, AI_AUTHENTICATION_FAILED", "404, AI_INVALID_RESPONSE",
            "409, AI_INVALID_RESPONSE", "503, AI_UNAVAILABLE", "500, AI_UNAVAILABLE", "400, AI_INVALID_RESPONSE",
            "302, AI_INVALID_RESPONSE", "202, AI_INVALID_RESPONSE"})
    @DisplayName("HTTP 오류를 구분하고 자동 재시도나 리다이렉트 없이 정적 오류를 반환한다")
    void get_failure_status(
            int status,
            RecordingErrorCode expected
    ) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    status,
                    "sensitive provider message",
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            Throwable thrown = catchThrowable(() -> client.get(ID));

            // then
            assertThat(thrown).isInstanceOf(RecordingException.class)
                    .satisfies(
                            exception -> assertThat(((RecordingException) exception).getErrorCode()).isEqualTo(expected)
                    )
                    .hasMessage(expected.getMessage());
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("접수 응답은 HTTP 200을 202로 오인하지 않는다")
    void submit_failure_status200() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    200,
                    jobBody(
                            "PROCESSING",
                            null
                    ),
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            Throwable thrown = catchThrowable(
                    () -> client.submit(
                            ID,
                            "https://audio.example/file"
                    )
            );

            // then
            assertThat(thrown).hasMessage(RecordingErrorCode.AI_INVALID_RESPONSE.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "not-json",
            "{\"attemptId\":\"019bbbc0-51ae-7000-8000-000000000002\",\"status\":\"PROCESSING\"}",
            "{\"attemptId\":\"019bbbc0-51ae-7000-8000-000000000001\",\"status\":\"UNKNOWN\"}",
            "{\"attemptId\":\"019bbbc0-51ae-7000-8000-000000000001\",\"status\":\"SUCCEEDED\"}",
            "{\"attemptId\":\"019bbbc0-51ae-7000-8000-000000000001\",\"status\":\"FAILED\"}",
            "{\"attemptId\":\"019bbbc0-51ae-7000-8000-000000000001\",\"status\":\"FAILED\","
                    + "\"failureCode\":\"https://secret?token=secret\"}"})
    @DisplayName("작업 ID와 상태 및 필수 결과가 잘못된 응답을 거절한다")
    void get_failure_invalidEnvelope(String body) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    200,
                    body,
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            Throwable thrown = catchThrowable(() -> client.get(ID));

            // then
            assertThat(thrown).hasMessage(RecordingErrorCode.AI_INVALID_RESPONSE.getMessage());
        }
    }

    @Test
    @DisplayName("헤더 뒤 본문이 멈춰도 전체 응답 기한 안에 종료한다")
    void get_failure_stalledBody() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    PATH,
                    exchange -> {
                        exchange.sendResponseHeaders(
                                200,
                                0
                        );
                        exchange.getResponseBody()
                                .write('{');
                        exchange.getResponseBody()
                                .flush();
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
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofMillis(400)
            );

            // when
            Throwable thrown = catchThrowable(() -> client.get(ID));

            // then
            assertThat(thrown).hasMessage(RecordingErrorCode.AI_TIMEOUT.getMessage());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("호출 중단 시 interrupt 표시와 원인을 보존한다")
    void get_failure_interrupted() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    200,
                    jobBody(
                            "PROCESSING",
                            null
                    ),
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );
            Thread.currentThread()
                    .interrupt();

            // when
            Throwable thrown = catchThrowable(() -> client.get(ID));

            // then
            assertThat(thrown).hasMessage(RecordingErrorCode.AI_INTERRUPTED.getMessage())
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(
                    Thread.currentThread()
                            .isInterrupted()
            ).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("응답 바이트 한도를 넘는 Content-Length는 본문을 받기 전에 거절한다")
    void get_failure_bodyLimit() throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            server.respond(
                    PATH,
                    exchange -> exchange.sendResponseHeaders(
                            200,
                            268_435_457L
                    )
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );

            // when
            Throwable thrown = catchThrowable(() -> client.get(ID));

            // then
            assertThat(thrown).hasMessage(RecordingErrorCode.AI_UNAVAILABLE.getMessage());
        }
    }

    private String succeeded() {
        return objectMapper.writeValueAsString(
                new AiTranscriptionJob(
                        ID,
                        AiTranscriptionJob.Status.SUCCEEDED,
                        null,
                        new AiTranscript(
                                1,
                                "{\"transcription\":[]}",
                                List.of(
                                        new AiTranscript.Utterance(
                                                0,
                                                "화자 1",
                                                .5,
                                                1.5,
                                                "안녕하세요"
                                        )
                                )
                        )
                )
        );
    }
    private String jobBody(
            String status,
            String failureCode
    ) {
        return objectMapper.writeValueAsString(
                new AiTranscriptionJob(
                        ID,
                        AiTranscriptionJob.Status.valueOf(status),
                        failureCode,
                        null
                )
        );
    }
    @ParameterizedTest
    @CsvSource({"404, JOB_NOT_FOUND, AI_JOB_NOT_FOUND", "409, BUSY, AI_BUSY", "503, NOT_READY, AI_NOT_READY"})
    @DisplayName("명시한 원격 오류 코드만 작업 소실과 접수 거절로 해석한다")
    void get_failure_confirmedRemoteCode(
            int status,
            String code,
            RecordingErrorCode expected
    ) throws Exception {
        // given
        try (LlmHttpServer server = new LlmHttpServer()) {
            respond(
                    server,
                    status,
                    "{\"code\":\"" + code + "\"}",
                    new AtomicReference<>()
            );
            AiTranscriptionHttpClient client = client(
                    server,
                    Duration.ofSeconds(2)
            );
            // when
            Throwable failure = catchThrowable(() -> client.get(ID));
            // then
            assertThat(failure).hasMessage(expected.getMessage());
        }
    }

    private void respond(
            LlmHttpServer server,
            int status,
            String body,
            AtomicReference<String> method
    ) {
        server.respond(
                PATH,
                exchange -> {
                    method.set(exchange.getRequestMethod());
                    exchange.getResponseHeaders()
                            .set(
                                    "Location",
                                    server.baseUrl() + "/unexpected"
                            );
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            status,
                            bytes.length
                    );
                    exchange.getResponseBody()
                            .write(bytes);
                }
        );
    }
    private AiTranscriptionHttpClient client(
            LlmHttpServer server,
            Duration timeout
    ) {
        TranscriptionProperties transcriptionProperties = new TranscriptionProperties(
                true,
                server.baseUrl(),
                "test-secret",
                Duration.ofSeconds(1),
                timeout,
                Duration.ofSeconds(2),
                Duration.ofHours(12)
        );
        return (AiTranscriptionHttpClient) new TranscriptionConfig().transcriptionClient(
                transcriptionProperties,
                objectMapper
        );
    }
}
