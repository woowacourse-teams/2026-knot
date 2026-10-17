package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingDetailResult;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.testsupport.LlmHttpServer;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RecordingTranscriptionFlowIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);
    private static final String RAW = "{\"transcription\":[{\"text\":\"회의 발언\"}]}";
    private static final String PATH = "/api/v1/transcription-jobs";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<UUID> SUBMITTED = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FAIL_FIRST_GET = new AtomicBoolean();
    private static final AtomicReference<CountDownLatch> FIRST_GET_ENTERED = emptyLatchReference();
    private static final AtomicReference<CountDownLatch> RELEASE_FIRST_GET = emptyLatchReference();
    private static final AtomicBoolean GET_NOT_FOUND = new AtomicBoolean();
    private static final AtomicBoolean VALID_AUTHORIZATION = new AtomicBoolean(true);
    private static final AtomicBoolean PREVIOUS_SAVED_BEFORE_SECOND_POST = new AtomicBoolean();
    private static final AtomicReference<JdbcClient> DATABASE = new AtomicReference<>();
    private static final LlmHttpServer SERVER = startServer();

    @Autowired
    private RecordingAudioUploadCompletionService completion;
    @Autowired
    private RecordingTranscriptionService worker;
    @Autowired
    private RecordingTranscriptionStateService recordingTranscriptionStateService;
    @Autowired
    private RecordingDetailService details;
    @Autowired
    private RecordingAudioUploadRepository recordingAudioUploadRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private RecordingAudioStorage recordingAudioStorage;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;

    @DynamicPropertySource
    static void transcriptionProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "knot.transcription.enabled",
                () -> true
        );
        registry.add(
                "knot.transcription.base-url",
                () -> SERVER.baseUrl()
                        .toString()
        );
        registry.add(
                "knot.transcription.token",
                () -> "test-token"
        );
        registry.add(
                "knot.transcription.poll-interval",
                () -> "PT0.01S"
        );
        registry.add(
                "knot.transcription.request-timeout",
                () -> "PT2S"
        );
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(recordingAudioStorage.findStoredObject(any())).thenReturn(
                StoredAudioObject.of(
                        1024,
                        "audio/webm",
                        "version-1"
                )
        );
        when(
                recordingAudioStorage.presignDownload(
                        any(),
                        any(),
                        any(),
                        anyLong()
                )
        ).thenReturn("https://immutable.example/audio.webm?versionId=fixture");
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("녹음자");
        fixtures.join(
                workspaceId,
                memberId
        );
        SUBMITTED.clear();
        GET_NOT_FOUND.set(false);
        FAIL_FIRST_GET.set(false);
        FIRST_GET_ENTERED.set(new CountDownLatch(0));
        RELEASE_FIRST_GET.set(new CountDownLatch(0));
        VALID_AUTHORIZATION.set(true);
        PREVIOUS_SAVED_BEFORE_SECOND_POST.set(false);
        DATABASE.set(jdbc);
    }

    @AfterEach
    void stopWorkerBeforeDatabaseShutdown() {
        worker.close();
    }

    @AfterAll
    static void stopHttpServer() {
        SERVER.close();
    }

    @Test
    @DisplayName("실제 HTTP 연동으로 전사와 문서 접수를 저장한 뒤 다음 녹음을 전사하며 완료 통지를 중복 처리하지 않는다")
    void complete_success_sequentialHttpAndPersistence() throws Exception {
        // given
        Upload first = reserveUpload();
        completion.complete(
                workspaceId,
                memberId,
                first.recordingId(),
                first.uploadId()
        );
        awaitStatus(
                first.recordingId(),
                TranscriptionStatus.SUCCEEDED
        );
        UUID firstAttempt = recordingTranscriptionStateService.findSnapshot(first.recordingId())
                .attemptId();
        completion.complete(
                workspaceId,
                memberId,
                first.recordingId(),
                first.uploadId()
        );
        assertThat(SUBMITTED).containsExactly(firstAttempt);
        Upload second = reserveUpload();

        // when
        completion.complete(
                workspaceId,
                memberId,
                second.recordingId(),
                second.uploadId()
        );

        // then
        awaitStatus(
                second.recordingId(),
                TranscriptionStatus.SUCCEEDED
        );
        assertThat(PREVIOUS_SAVED_BEFORE_SECOND_POST).isTrue();
        assertThat(VALID_AUTHORIZATION).isTrue();
        assertThat(SUBMITTED).containsExactly(
                firstAttempt,
                recordingTranscriptionStateService.findSnapshot(second.recordingId())
                        .attemptId()
        );
        assertThat(
                jdbc.sql("SELECT content FROM transcripts ORDER BY id")
                        .query(String.class)
                        .list()
        ).containsExactly(
                RAW,
                RAW
        );
        assertThat(
                jdbc.sql("SELECT text FROM transcript_segments ORDER BY transcript_id, position")
                        .query(String.class)
                        .list()
        ).containsExactly(
                "첫 발언",
                "다른 발언",
                "첫 발언",
                "다른 발언"
        );
        assertThat(
                jdbc.sql("SELECT start_millis FROM transcript_segments ORDER BY transcript_id, position")
                        .query(Long.class)
                        .list()
        ).containsExactly(
                125L,
                1000L,
                125L,
                1000L
        );
        assertThat(count("document_generation_batches")).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT stage FROM document_generation_jobs ORDER BY id")
                        .query(String.class)
                        .list()
        ).containsExactly(
                "CLASSIFICATION",
                "CLASSIFICATION"
        );
        RecordingDetailResult detail = details.find(
                workspaceId,
                memberId,
                second.recordingId()
        );
        assertThat(detail.audioUploadStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        assertThat(detail.transcriptionStatus()).isEqualTo(TranscriptionStatus.SUCCEEDED);
        assertThat(detail.transcriptionAttemptId()).isEqualTo(SUBMITTED.get(1));
        assertThat(detail.transcriptionFailureCode()).isNull();
    }

    @Test
    @DisplayName("AI 메모리 유실의 404는 같은 시도와 version으로 복구해 문서를 한 번 접수한다")
    void complete_success_missingAiJobRecoversSameAttempt() throws Exception {
        // given
        GET_NOT_FOUND.set(true);
        Upload upload = reserveUpload();
        // when
        completion.complete(
                workspaceId,
                memberId,
                upload.recordingId(),
                upload.uploadId()
        );
        // then
        awaitStatus(
                upload.recordingId(),
                TranscriptionStatus.SUCCEEDED
        );
        assertThat(SUBMITTED).hasSize(2);
        assertThat(SUBMITTED.get(0)).isEqualTo(SUBMITTED.get(1));
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("업로드 완료 때 확인한 저장소 버전을 저장하고 전사 입력 URL을 그 버전으로 서명한다")
    void complete_success_signsVersionVerifiedAtCompletion() throws Exception {
        // given
        Upload upload = reserveUpload();

        // when
        completion.complete(
                workspaceId,
                memberId,
                upload.recordingId(),
                upload.uploadId()
        );

        // then
        awaitStatus(
                upload.recordingId(),
                TranscriptionStatus.SUCCEEDED
        );
        assertThat(
                jdbc.sql("SELECT storage_version_id FROM recording_audio_uploads WHERE id = :id")
                        .param(
                                "id",
                                upload.uploadId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("version-1");
        verify(recordingAudioStorage).presignDownload(
                "recordings/" + upload.recordingId() + "/audio.webm",
                "version-1",
                "audio/webm",
                1024L
        );
    }

    @Test
    @DisplayName("전사 접수 전 삭제된 오디오의 완료 통지는 전사를 새로 만들지 않는다")
    void repeatedCompletionDoesNotTranscribeDeletedAudio() {
        // given
        Upload upload = reserveUpload();
        transactions.executeWithoutResult(status -> {
            RecordingAudioUpload audio = recordingAudioUploadRepository.findById(upload.uploadId())
                    .orElseThrow();
            audio.complete(
                    StoredAudioObject.of(
                            1024,
                            "audio/webm",
                            "version-1"
                    ),
                    NOW
            );
            audio.recordDeleted(NOW);
        });

        // when
        RecordingAudioUploadCompletionResult result = completion.complete(
                workspaceId,
                memberId,
                upload.recordingId(),
                upload.uploadId()
        );

        // then
        assertThat(result.uploadStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        assertThat(recordingTranscriptionStateService.findSnapshot(upload.recordingId())).isNull();
        assertThat(SUBMITTED).isEmpty();
    }

    @Test
    @DisplayName("앞 전사가 실패하면 이미 대기 중인 다음 녹음을 자동으로 실행해 문서를 접수한다")
    void complete_success_drainsQueuedAfterRemoteFailure() throws Exception {
        // given
        FAIL_FIRST_GET.set(true);
        FIRST_GET_ENTERED.set(new CountDownLatch(1));
        RELEASE_FIRST_GET.set(new CountDownLatch(1));
        Upload first = reserveUpload();
        Upload second = reserveUpload();
        completion.complete(
                workspaceId,
                memberId,
                first.recordingId(),
                first.uploadId()
        );
        assertThat(
                FIRST_GET_ENTERED.get()
                        .await(
                                5,
                                TimeUnit.SECONDS
                        )
        ).isTrue();
        completion.complete(
                workspaceId,
                memberId,
                second.recordingId(),
                second.uploadId()
        );
        assertThat(
                recordingTranscriptionStateService.findSnapshot(second.recordingId())
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
        // when
        RELEASE_FIRST_GET.get()
                .countDown();
        // then
        awaitStatus(
                first.recordingId(),
                TranscriptionStatus.FAILED
        );
        awaitStatus(
                second.recordingId(),
                TranscriptionStatus.SUCCEEDED
        );
        assertThat(SUBMITTED).hasSize(2);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
    }

    private Upload reserveUpload() {
        long recording = fixtures.saveRecording(
                workspaceId,
                memberId,
                30_000
        );
        long upload = transactions.execute(
                status -> recordingAudioUploadRepository.save(
                        RecordingAudioUpload.reserve(
                                recording,
                                "recordings/" + recording + "/audio.webm",
                                "audio/webm",
                                1024,
                                NOW.minusSeconds(10)
                        )
                )
                        .getId()
        );
        return new Upload(
                recording,
                upload
        );
    }

    private void awaitStatus(
            long recordingId,
            TranscriptionStatus status
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (recordingTranscriptionStateService.findSnapshot(recordingId) != null
                    && recordingTranscriptionStateService.findSnapshot(recordingId)
                            .status() == status) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(status);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private static LlmHttpServer startServer() {
        try {
            LlmHttpServer server = new LlmHttpServer();
            server.respond(
                    PATH,
                    RecordingTranscriptionFlowIntegrationTest::respond
            );
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        if (!"Bearer test-token".equals(
                exchange.getRequestHeaders()
                        .getFirst("Authorization")
        )) {
            VALID_AUTHORIZATION.set(false);
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            JsonNode request = MAPPER.readTree(SERVER.requestBody());
            UUID attempt = UUID.fromString(
                    request.path("attemptId")
                            .asString()
            );
            SUBMITTED.add(attempt);
            if (SUBMITTED.size() == 2) {
                PREVIOUS_SAVED_BEFORE_SECOND_POST.set(
                        DATABASE.get()
                                .sql("SELECT count(*) FROM recording_transcriptions WHERE status = 'SUCCEEDED'")
                                .query(Long.class)
                                .single() == 1
                );
            }
            send(
                    exchange,
                    202,
                    MAPPER.createObjectNode()
                            .put(
                                    "attemptId",
                                    attempt.toString()
                            )
                            .put(
                                    "status",
                                    "PROCESSING"
                            )
            );
            return;
        }
        if (GET_NOT_FOUND.getAndSet(false)) {
            send(
                    exchange,
                    404,
                    MAPPER.createObjectNode()
                            .put(
                                    "code",
                                    "JOB_NOT_FOUND"
                            )
            );
            return;
        }
        String path = exchange.getRequestURI()
                .getPath();
        String attempt = path.substring((PATH + "/").length());
        if (FAIL_FIRST_GET.getAndSet(false)) {
            FIRST_GET_ENTERED.get()
                    .countDown();
            awaitFirstRelease();
            send(
                    exchange,
                    200,
                    MAPPER.createObjectNode()
                            .put(
                                    "attemptId",
                                    attempt
                            )
                            .put(
                                    "status",
                                    "FAILED"
                            )
                            .put(
                                    "failureCode",
                                    "INFERENCE_FAILED"
                            )
            );
            return;
        }
        ObjectNode body = MAPPER.createObjectNode()
                .put(
                        "attemptId",
                        attempt
                )
                .put(
                        "status",
                        "SUCCEEDED"
                );
        ObjectNode result = body.putObject("result")
                .put(
                        "schemaVersion",
                        1
                )
                .put(
                        "rawWhisperJson",
                        RAW
                );
        result.set(
                "utterances",
                MAPPER.readTree("""
                        [{"ord":0,"speakerLabel":"speaker-A","startSec":0.125,"endSec":0.8,"text":"첫 발언"},
                         {"ord":1,"speakerLabel":"speaker-B","startSec":1,"endSec":1.5,"text":"다른 발언"}]
                        """)
        );
        send(
                exchange,
                200,
                body
        );
    }

    private static AtomicReference<CountDownLatch> emptyLatchReference() {
        return new AtomicReference<>(new CountDownLatch(0));
    }

    private static void awaitFirstRelease() throws IOException {
        try {
            if (!RELEASE_FIRST_GET.get()
                    .await(
                            5,
                            TimeUnit.SECONDS
                    )) {
                throw new IOException("검증 응답 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IOException(
                    "검증 응답이 중단되었습니다",
                    exception
            );
        }
    }

    private static void send(
            HttpExchange exchange,
            int status,
            JsonNode body
    ) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json"
                );
        exchange.sendResponseHeaders(
                status,
                bytes.length
        );
        exchange.getResponseBody()
                .write(bytes);
    }

    private record Upload(
            long recordingId,
            long uploadId
    ) {
    }
}
