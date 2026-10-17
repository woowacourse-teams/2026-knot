package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionSnapshot;
import com.knot.backend.recording.domain.RecordingAudioUpload;
import com.knot.backend.recording.domain.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.StoredAudioObject;
import com.knot.backend.recording.domain.TranscriptionStatus;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * MockMvc runs the HTTP controller/filter boundary; the AI client calls the
 * opt-in Mac validation server, possibly through a test SSH relay. This is not
 * a production EC2 to Mac route. PostgreSQL is real Testcontainers. Storage
 * metadata and signed URLs are stubs; the AI server serves synthetic audio
 * through its storage fixture. Document generation is admitted but no LLM is
 * called.
 */
@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = "knot.document-generation.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@EnabledIfEnvironmentVariable(named = "KNOT_REAL_AI_OBSERVABILITY", matches = "true")
class RecordingObservabilityFlowIntegrationTest {
    private static final String FIXTURE_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/";
    private static final List<String> EVIDENCE_FIELDS = List.of(
            "event",
            "outcome",
            "requestId",
            "recordingId",
            "uploadId",
            "attemptId",
            "elapsedMs",
            "errorCode"
    );

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private RecordingAudioUploadRepository uploads;
    @Autowired
    private RecordingTranscriptionStateService state;
    @Autowired
    private RecordingTranscriptionService worker;
    @Autowired
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    @MockitoSpyBean
    private DocumentGenerationIntakeService intake;
    private DocumentFixtures fixtures;
    private LogCapture logs;
    private long workspaceId;
    private long memberId;
    private String testName;

    @DynamicPropertySource
    static void realAiProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "knot.transcription.enabled",
                () -> true
        );
        registry.add(
                "knot.transcription.base-url",
                () -> requiredEnvironment("KNOT_REAL_AI_BASE_URL")
        );
        registry.add(
                "knot.transcription.token",
                () -> requiredEnvironment("KNOT_REAL_AI_TOKEN")
        );
        registry.add(
                "knot.transcription.connect-timeout",
                () -> "PT5S"
        );
        registry.add(
                "knot.transcription.request-timeout",
                () -> "PT15S"
        );
        registry.add(
                "knot.transcription.poll-interval",
                () -> "PT0.25S"
        );
        registry.add(
                "knot.transcription.job-timeout",
                () -> "PT2M"
        );
    }

    @BeforeEach
    void prepare(TestInfo test) {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("관측 검증자");
        fixtures.join(
                workspaceId,
                memberId
        );
        when(storage.findStoredObject(any())).thenReturn(
                StoredAudioObject.of(
                        1024,
                        "audio/webm",
                        "fixture-version"
                )
        );
        when(
                storage.presignDownload(
                        any(),
                        any(),
                        any(),
                        anyLong()
                )
        ).thenReturn(FIXTURE_URL + "audio.webm?fixture=1");
        testName = test.getTestMethod()
                .orElseThrow()
                .getName();
        logs = new LogCapture(RecordingLog.class);
    }

    @AfterEach
    void close() throws Exception {
        try {
            worker.close();
            writeEvidence();
        } finally {
            logs.close();
            jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                    .update();
        }
    }

    @Test
    @DisplayName("실제 AI 전사 성공을 HTTP requestId부터 전사와 문서 접수 커밋까지 연결한다")
    void complete_success_realAiAndCommittedTranscript() throws Exception {
        // given
        Upload upload = reserveUpload();
        long deadline = deadline();

        // when
        MvcResult response = complete(upload);

        // then
        RecordingTranscriptionSnapshot snapshot = awaitTerminal(
                upload,
                TranscriptionStatus.SUCCEEDED,
                deadline
        );
        assertCorrelation(
                response,
                upload,
                snapshot.attemptId()
        );
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isPositive();
        assertThat(count("document_generation_batches")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertEvent(
                "recording.transcription.result",
                "completed",
                snapshot.attemptId()
        );
        assertEvent(
                "recording.transcription.persist",
                "completed",
                snapshot.attemptId()
        );
        assertThat(snapshot.failureCode()).isNull();
    }

    @Test
    @DisplayName("깨진 합성 WebM의 실제 디코딩 실패는 원인과 실패 상태만 커밋한다")
    void complete_failure_realAiDecode() throws Exception {
        // given
        when(
                storage.presignDownload(
                        any(),
                        any(),
                        any(),
                        anyLong()
                )
        ).thenReturn(FIXTURE_URL + "broken.webm?fixture=1");
        Upload upload = reserveUpload();
        long deadline = deadline();

        // when
        MvcResult response = complete(upload);

        // then
        RecordingTranscriptionSnapshot snapshot = awaitTerminal(
                upload,
                TranscriptionStatus.FAILED,
                deadline
        );
        assertCorrelation(
                response,
                upload,
                snapshot.attemptId()
        );
        assertThat(snapshot.failureCode()).isEqualTo("DECODE_FAILED");
        assertNoStoredTranscript();
        assertEvent(
                "recording.transcription.result",
                "failed",
                snapshot.attemptId()
        );
        assertEvent(
                "recording.transcription.persist",
                "failed",
                snapshot.attemptId()
        );
        assertThat(fields()).noneMatch(
                event -> matches(
                        event,
                        "recording.transcription.persist",
                        "completed"
                )
        );
    }

    @Test
    @DisplayName("실제 AI 성공 결과를 받은 뒤 문서 접수 예외가 발생하면 전체 저장을 롤백한다")
    void complete_failure_controlledDatabaseRollbackAfterAiSuccess() throws Exception {
        // given
        transactions.executeWithoutResult(
                transaction -> doThrow(new IllegalStateException("관측 검증용 문서 접수 실패")).when(intake)
                        .acceptCompletedTranscript(
                                anyLong(),
                                anyLong(),
                                anyLong()
                        )
        );
        Upload upload = reserveUpload();
        long deadline = deadline();

        // when
        MvcResult response = complete(upload);

        // then
        RecordingTranscriptionSnapshot snapshot = awaitTerminal(
                upload,
                TranscriptionStatus.FAILED,
                deadline
        );
        assertCorrelation(
                response,
                upload,
                snapshot.attemptId()
        );
        assertThat(snapshot.failureCode()).isEqualTo("RESULT_STORAGE_FAILED");
        assertNoStoredTranscript();
        assertEvent(
                "recording.transcription.result",
                "completed",
                snapshot.attemptId()
        );
        assertEvent(
                "recording.transcription.persist",
                "failed",
                snapshot.attemptId()
        );
        assertThat(fields()).anySatisfy(
                event -> assertThat(event).containsEntry(
                        "event",
                        "recording.transcription.persist"
                )
                        .containsEntry(
                                "outcome",
                                "failed"
                        )
                        .containsEntry(
                                "errorCode",
                                "INTERNAL_SERVER_ERROR"
                        )
        );
        assertThat(fields()).noneMatch(
                event -> matches(
                        event,
                        "recording.transcription.persist",
                        "completed"
                )
        );
    }

    private Upload reserveUpload() {
        long recording = fixtures.saveRecording(
                workspaceId,
                memberId,
                30_000
        );
        Long upload = transactions.execute(
                status -> uploads.save(
                        RecordingAudioUpload.reserve(
                                recording,
                                "recordings/" + recording + "/audio.webm",
                                "audio/webm",
                                1024,
                                clock.instant()
                                        .minusSeconds(10)
                        )
                )
                        .getId()
        );
        return new Upload(
                recording,
                upload
        );
    }

    private MvcResult complete(Upload upload) throws Exception {
        MvcResult csrfResponse = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = csrfResponse.getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        String csrfToken = mapper.readTree(
                csrfResponse.getResponse()
                        .getContentAsString()
        )
                .path("token")
                .asText();
        Cookie access = new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "관측 검증자",
                                null
                        )
                )
        );
        return mvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-complete",
                        workspaceId,
                        upload.recordingId()
                ).cookie(
                        access,
                        csrf
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "uploadId",
                                                upload.uploadId()
                                        )
                                )
                        )
        )
                .andExpect(status().isOk())
                .andReturn();
    }

    private RecordingTranscriptionSnapshot awaitTerminal(
            Upload upload,
            TranscriptionStatus expected,
            long deadline
    ) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            RecordingTranscriptionSnapshot snapshot = state.find(upload.recordingId());
            if (snapshot != null && snapshot.status() == expected && fields().stream()
                    .anyMatch(
                            event -> matches(
                                    event,
                                    "recording.transcription.persist",
                                    expectedOutcome(expected)
                            )
                    )) {
                return snapshot;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("실제 AI 관측 검증이 2분 안에 기대한 종료 상태에 도달하지 않았습니다");
    }

    private String expectedOutcome(TranscriptionStatus status) {
        if (status == TranscriptionStatus.SUCCEEDED) {
            return "completed";
        }
        return "failed";
    }

    private void assertCorrelation(
            MvcResult response,
            Upload upload,
            UUID attempt
    ) {
        String requestId = response.getResponse()
                .getHeader("X-Request-ID");
        assertThat(requestId).matches("[0-9a-f]{32}");
        assertThat(fields()).anySatisfy(
                event -> assertThat(event).containsEntry(
                        "event",
                        "recording.upload.complete"
                )
                        .containsEntry(
                                "outcome",
                                "completed"
                        )
                        .containsEntry(
                                "requestId",
                                requestId
                        )
                        .containsEntry(
                                "recordingId",
                                upload.recordingId()
                        )
                        .containsEntry(
                                "uploadId",
                                upload.uploadId()
                        )
        );
        assertThat(fields()).anySatisfy(
                event -> assertThat(event).containsEntry(
                        "event",
                        "recording.transcription.accept"
                )
                        .containsEntry(
                                "outcome",
                                "accepted"
                        )
                        .containsEntry(
                                "requestId",
                                requestId
                        )
                        .containsEntry(
                                "attemptId",
                                attempt.toString()
                        )
                        .containsEntry(
                                "recordingId",
                                upload.recordingId()
                        )
        );
        assertThat(
                fields().stream()
                        .filter(
                                event -> event.get("attemptId") != null
                                        && !"recording.transcription.accept".equals(event.get("event"))
                        )
                        .toList()
        ).isNotEmpty()
                .allSatisfy(
                        event -> assertThat(event).containsEntry(
                                "attemptId",
                                attempt.toString()
                        )
                                .containsEntry(
                                        "recordingId",
                                        upload.recordingId()
                                )
                                .containsEntry(
                                        "requestId",
                                        null
                                )
                );
    }

    private void assertEvent(
            String event,
            String outcome,
            UUID attempt
    ) {
        assertThat(fields()).anySatisfy(
                record -> assertThat(record).containsEntry(
                        "event",
                        event
                )
                        .containsEntry(
                                "outcome",
                                outcome
                        )
                        .containsEntry(
                                "attemptId",
                                attempt.toString()
                        )
        );
    }

    private void assertNoStoredTranscript() {
        assertThat(count("transcripts")).isZero();
        assertThat(count("transcript_segments")).isZero();
        assertThat(count("document_generation_batches")).isZero();
        assertThat(count("document_generation_jobs")).isZero();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private List<Map<String, Object>> fields() {
        return logs.events()
                .stream()
                .map(this::fields)
                .toList();
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "timestamp",
                event.getTimeStamp()
        );
        if (event.getKeyValuePairs() == null) {
            return result;
        }
        event.getKeyValuePairs()
                .stream()
                .filter(pair -> EVIDENCE_FIELDS.contains(pair.key))
                .forEach(
                        pair -> result.put(
                                pair.key,
                                pair.value
                        )
                );
        return result;
    }

    private boolean matches(
            Map<String, Object> record,
            String event,
            String outcome
    ) {
        return event.equals(record.get("event")) && outcome.equals(record.get("outcome"));
    }

    private void writeEvidence() throws Exception {
        String output = System.getenv("KNOT_OBSERVABILITY_EVIDENCE_DIR");
        if (output == null || output.isBlank()) {
            return;
        }
        Path directory = Path.of(output);
        Files.createDirectories(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        );
        Path target = directory.resolve("backend-" + testName + ".json");
        Path temporary = Files.createTempFile(
                directory,
                "backend-evidence-",
                ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        );
        try {
            Files.writeString(
                    temporary,
                    mapper.writeValueAsString(fields()) + "\n",
                    StandardCharsets.UTF_8
            );
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("실제 AI 관측 검증의 필수 환경 변수가 없습니다");
        }
        return value;
    }

    private record Upload(
            long recordingId,
            long uploadId
    ) {
    }
}
