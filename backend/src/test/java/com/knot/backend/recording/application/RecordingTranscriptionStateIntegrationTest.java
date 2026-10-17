package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.document.application.DocumentClassificationResultService;
import com.knot.backend.document.application.DocumentRetentionCleanupService;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.document.domain.DocumentGenerationJobRepository;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.domain.RecordingAudioUpload;
import com.knot.backend.recording.domain.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingSession;
import com.knot.backend.recording.domain.RecordingSessionRepository;
import com.knot.backend.recording.domain.RecordingTranscription;
import com.knot.backend.recording.domain.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.StoredAudioObject;
import com.knot.backend.recording.domain.TranscriptionStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class RecordingTranscriptionStateIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);

    @Autowired
    private RecordingTranscriptionStateService state;
    @Autowired
    private DocumentClassificationResultService classifications;
    @Autowired
    private DocumentRetentionCleanupService retention;
    @Autowired
    private DocumentGenerationJobRepository generationJobs;
    @Autowired
    private RecordingSessionRepository recordings;
    @Autowired
    private RecordingAudioUploadRepository uploads;
    @Autowired
    private RecordingTranscriptionRepository transcriptions;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @MockitoSpyBean
    private DocumentGenerationIntakeService intake;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long recordingId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("녹음자");
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = completedRecording(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("같은 업로드 완료 통지를 반복해도 전사 시도는 하나만 등록한다")
    void registerAutomatic_success_idempotent() {
        // given
        register(
                workspaceId,
                recordingId
        );
        UUID attempt = state.find(recordingId)
                .attemptId();
        // when
        register(
                workspaceId,
                recordingId
        );
        // then
        assertThat(count("recording_transcriptions")).isEqualTo(1);
        assertThat(
                state.find(recordingId)
                        .attemptId()
        ).isEqualTo(attempt);
        assertThat(state.findProcessing()).isPresent();
    }

    @Test
    @DisplayName("원문·발화·문서 생성 접수와 전사 성공을 같은 트랜잭션에 저장한다")
    void complete_success_atomicAndIdempotent() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        AiTranscript result = transcript();
        // when
        state.complete(
                execution,
                result
        );
        state.complete(
                execution,
                result
        );
        // then
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(3);
        assertThat(count("document_generation_batches")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.SUCCEEDED);
        assertThat(
                jdbc.sql("SELECT content FROM transcripts")
                        .query(String.class)
                        .single()
        ).isEqualTo(result.rawWhisperJson());
        assertThat(
                jdbc.sql("SELECT speaker_number FROM transcript_segments ORDER BY position")
                        .query(Integer.class)
                        .list()
        ).containsExactly(
                1,
                2,
                1
        );
        assertThat(
                jdbc.sql("SELECT start_millis FROM transcript_segments ORDER BY position")
                        .query(Long.class)
                        .list()
        ).containsExactly(
                125L,
                1000L,
                2000L
        );
    }

    @Test
    @DisplayName("문서 접수가 실패하면 원문·발화·완료 상태도 모두 롤백한다")
    void complete_failure_intakeRollback() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        transactions.executeWithoutResult(
                status -> doThrow(new IllegalStateException("intake unavailable")).when(intake)
                        .acceptCompletedTranscript(
                                anyLong(),
                                anyLong(),
                                anyLong()
                        )
        );
        // when
        Throwable failure = catchThrowable(
                () -> state.complete(
                        execution,
                        transcript()
                )
        );
        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(count("transcripts")).isZero();
        assertThat(count("transcript_segments")).isZero();
        assertThat(count("document_generation_batches")).isZero();
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
    }

    @Test
    @DisplayName("실패 재시도 후 이전 시도의 결과와 실패 통지는 무시한다")
    void retry_success_ignoresOldAttempt() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution previous = state.findProcessing()
                .orElseThrow();
        state.fail(
                previous,
                "AI_TIMEOUT"
        );
        // when
        RecordingTranscriptionRetryResult retry = state.retry(
                workspaceId,
                memberId,
                recordingId
        );
        state.complete(
                previous,
                transcript()
        );
        state.fail(
                previous,
                "LATE_FAILURE"
        );
        // then
        assertThat(retry.attemptId()).isNotEqualTo(previous.attemptId());
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
        assertThat(
                state.find(recordingId)
                        .attemptId()
        ).isEqualTo(retry.attemptId());
        assertThat(count("transcripts")).isZero();
    }

    @Test
    @DisplayName("다른 워크스페이스의 동시 등록도 하나만 실행하고 나머지는 BUSY로 기록한다")
    void registerAutomatic_success_globalAdmission() throws Exception {
        // given
        long otherWorkspace = fixtures.saveWorkspace();
        long otherMember = fixtures.saveMember("다른 녹음자");
        fixtures.join(
                otherWorkspace,
                otherMember
        );
        long otherRecording = completedRecording(
                otherWorkspace,
                otherMember
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(
                    () -> awaitAndRegister(
                            barrier,
                            workspaceId,
                            recordingId
                    )
            );
            Future<?> second = executor.submit(
                    () -> awaitAndRegister(
                            barrier,
                            otherWorkspace,
                            otherRecording
                    )
            );
            first.get(
                    10,
                    TimeUnit.SECONDS
            );
            second.get(
                    10,
                    TimeUnit.SECONDS
            );
        }
        // then
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions ORDER BY id")
                        .query(String.class)
                        .list()
        ).containsExactlyInAnyOrder(
                "PROCESSING",
                "FAILED"
        );
        assertThat(
                jdbc.sql("SELECT failure_code FROM recording_transcriptions WHERE status = 'FAILED'")
                        .query(String.class)
                        .single()
        ).isEqualTo("BUSY");
    }

    @Test
    @DisplayName("다른 작업 실행 중 재시도는 현재 실패 시도를 유지하며 거부한다")
    void retry_failure_busyPreservesAttempt() {
        // given
        register(
                workspaceId,
                recordingId
        );
        long nextRecording = completedRecording(
                workspaceId,
                memberId
        );
        register(
                workspaceId,
                nextRecording
        );
        UUID failedAttempt = state.find(nextRecording)
                .attemptId();
        // when
        Throwable failure = catchThrowable(
                () -> state.retry(
                        workspaceId,
                        memberId,
                        nextRecording
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                state.find(nextRecording)
                        .attemptId()
        ).isEqualTo(failedAttempt);
        assertThat(
                state.find(nextRecording)
                        .status()
        ).isEqualTo(TranscriptionStatus.FAILED);
    }

    @Test
    @DisplayName("성공한 작업은 재시도하지 않고 결과를 유지한다")
    void retry_failure_succeeded() {
        // given
        register(
                workspaceId,
                recordingId
        );
        state.complete(
                state.findProcessing()
                        .orElseThrow(),
                transcript()
        );
        // when
        Throwable failure = catchThrowable(
                () -> state.retry(
                        workspaceId,
                        memberId,
                        recordingId
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(count("transcripts")).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원은 실패한 녹음을 재시도할 수 없다")
    void retry_failure_otherMember() {
        // given
        register(
                workspaceId,
                recordingId
        );
        state.fail(
                state.findProcessing()
                        .orElseThrow(),
                "AI_TIMEOUT"
        );
        long otherMember = fixtures.saveMember("다른 회원");
        fixtures.join(
                workspaceId,
                otherMember
        );
        // when
        Throwable failure = catchThrowable(
                () -> state.retry(
                        workspaceId,
                        otherMember,
                        recordingId
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.FAILED);
    }

    @Test
    @DisplayName("삭제된 워크스페이스의 늦은 결과는 원문이나 문서를 되살리지 않는다")
    void complete_success_deletedWorkspaceDiscarded() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        jdbc.sql("UPDATE workspaces SET deleted_at = :now WHERE id = :id")
                .param(
                        "now",
                        Timestamp.from(NOW)
                )
                .param(
                        "id",
                        workspaceId
                )
                .update();
        // when
        state.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_batches")).isZero();
        assertThat(
                state.find(recordingId)
                        .failureCode()
        ).isEqualTo("RECORDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("재시작 복구는 실행 작업을 실패 처리하고 자동으로 재접수하지 않는다")
    void recoverProcessing_success_explicitRetryOnly() {
        // given
        register(
                workspaceId,
                recordingId
        );
        UUID attempt = state.find(recordingId)
                .attemptId();
        // when
        state.recoverProcessing();
        state.recoverProcessing();
        register(
                workspaceId,
                recordingId
        );
        // then
        assertThat(state.findProcessing()).isEmpty();
        assertThat(
                state.find(recordingId)
                        .attemptId()
        ).isEqualTo(attempt);
        assertThat(
                state.find(recordingId)
                        .failureCode()
        ).isEqualTo("SERVER_RESTARTED");
        assertThat(count("recording_transcriptions")).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 제약은 직접 저장으로 전역 실행 슬롯을 우회하는 것을 거부한다")
    void save_failure_globalConstraint() {
        // given
        register(
                workspaceId,
                recordingId
        );
        long otherRecording = completedRecording(
                workspaceId,
                memberId
        );
        // when
        Throwable failure = catchThrowable(
                () -> transactions.executeWithoutResult(
                        status -> transcriptions.saveAndFlush(
                                RecordingTranscription.processing(
                                        otherRecording,
                                        UUID.randomUUID(),
                                        NOW
                                )
                        )
                )
        );
        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("recording_transcriptions")).isEqualTo(1);
    }

    @Test
    @DisplayName("녹음이 폐기된 뒤 도착한 결과는 전사나 문서로 저장하지 않는다")
    void complete_success_discardedRecording() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        jdbc.sql("UPDATE recording_sessions SET status = 'DISCARDED' WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        // when
        state.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(
                state.find(recordingId)
                        .failureCode()
        ).isEqualTo("RECORDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("DB 제약은 실패 코드 없는 실패 상태를 거부한다")
    void save_failure_inconsistentTerminalState() {
        // given
        register(
                workspaceId,
                recordingId
        );
        // when
        Throwable failure = catchThrowable(
                () -> jdbc.sql("""
                        UPDATE recording_transcriptions SET status = 'FAILED'
                        WHERE recording_session_id = :recordingId
                        """)
                        .param(
                                "recordingId",
                                recordingId
                        )
                        .update()
        );
        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
    }

    @Test
    @DisplayName("내용 없음 원문 보존 기한 정리는 성공 상태를 유지하며 원문과 발화를 삭제한다")
    void complete_success_retentionReleasesTranscript() {
        // given
        register(
                workspaceId,
                recordingId
        );
        state.complete(
                state.findProcessing()
                        .orElseThrow(),
                transcript()
        );
        long jobId = jdbc.sql("SELECT id FROM document_generation_jobs")
                .query(Long.class)
                .single();
        long batchId = jdbc.sql("SELECT id FROM document_generation_batches")
                .query(Long.class)
                .single();
        transactions.executeWithoutResult(
                status -> generationJobs.findByWorkspaceIdAndIdForUpdate(
                        workspaceId,
                        jobId
                )
                        .orElseThrow()
                        .startRunning(NOW)
        );
        classifications.completeClassification(
                workspaceId,
                jobId,
                1,
                DocumentTopicClassificationResult.fromNames(List.of())
        );
        // when
        retention.cleanup(
                workspaceId,
                recordingId,
                batchId
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(count("transcript_segments")).isZero();
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @MethodSource("invalidTranscripts")
    @DisplayName("빈 원문·발화 또는 잘못된 순서·시각은 부분 저장이나 문서 접수를 남기지 않는다")
    void complete_failure_invalidTranscriptRollback(AiTranscript invalid) {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        // when
        Throwable failure = catchThrowable(
                () -> state.complete(
                        execution,
                        invalid
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(count("transcripts")).isZero();
        assertThat(count("transcript_segments")).isZero();
        assertThat(count("document_generation_batches")).isZero();
        assertThat(
                state.find(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
    }

    private static Stream<AiTranscript> invalidTranscripts() {
        AiTranscript.Utterance first = new AiTranscript.Utterance(
                0,
                "speaker",
                1,
                2,
                "첫 발화"
        );
        return Stream.of(
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        -1,
                                        2,
                                        "음수 시각"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        1,
                                        7201,
                                        "상한 초과"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        Double.NaN,
                                        2,
                                        "잘못된 시각"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        1,
                                        Double.NaN,
                                        "잘못된 종료"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        Double.POSITIVE_INFINITY,
                                        2,
                                        "무한 시각"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        1,
                                        Double.POSITIVE_INFINITY,
                                        "무한 종료"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        " ",
                        List.of(first)
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of()
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        1,
                                        "speaker",
                                        1,
                                        2,
                                        "순서 누락"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                new AiTranscript.Utterance(
                                        0,
                                        "speaker",
                                        1,
                                        .9999,
                                        "역전된 종료 시각"
                                )
                        )
                ),
                new AiTranscript(
                        1,
                        "{}",
                        List.of(
                                first,
                                new AiTranscript.Utterance(
                                        1,
                                        "speaker",
                                        .5,
                                        1.5,
                                        "역전된 시각"
                                )
                        )
                )
        );
    }

    @Test
    @DisplayName("자동 전사 접수는 커밋 이후 새 attempt를 accepted로 기록한다")
    void logging_accept_afterCommit() {
        // given
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            register(
                    workspaceId,
                    recordingId
            );

            // then
            UUID attempt = state.find(recordingId)
                    .attemptId();
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "accepted"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                hasLogField(
                                        event,
                                        "event",
                                        "recording.transcription.accept"
                                )
                        ).isTrue();
                        assertThat(
                                hasLogField(
                                        event,
                                        "attemptId",
                                        attempt
                                )
                        ).isTrue();
                        assertThat(
                                hasLogField(
                                        event,
                                        "workspaceId",
                                        workspaceId
                                )
                        ).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("자동 접수의 BUSY는 실제 실패 상태와 접수 거절 로그를 함께 남긴다")
    void logging_accept_busyRejected() {
        // given
        register(
                workspaceId,
                recordingId
        );
        long otherRecording = completedRecording(
                workspaceId,
                memberId
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            register(
                    workspaceId,
                    otherRecording
            );

            // then
            assertThat(
                    state.find(otherRecording)
                            .status()
            ).isEqualTo(TranscriptionStatus.FAILED);
            assertThat(
                    state.find(otherRecording)
                            .failureCode()
            ).isEqualTo("BUSY");
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "rejected"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                hasLogField(
                                        event,
                                        "errorCode",
                                        "BUSY"
                                )
                        ).isTrue();
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    });
            assertThat(logs.events()).noneMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "accepted"
                    )
            );
        }
    }

    @Test
    @DisplayName("자동 접수 재요청은 기존 attemptId의 replayed를 기록한다")
    void logging_accept_replayed() {
        // given
        register(
                workspaceId,
                recordingId
        );
        UUID attempt = state.find(recordingId)
                .attemptId();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            register(
                    workspaceId,
                    recordingId
            );

            // then
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "replayed"
                    )
            )
                    .singleElement()
                    .satisfies(
                            event -> assertThat(
                                    hasLogField(
                                            event,
                                            "attemptId",
                                            attempt
                                    )
                            ).isTrue()
                    );
        }
    }

    @Test
    @DisplayName("전사 저장 성공은 문서 생성 완료가 아닌 persist completed로 기록한다")
    void logging_complete_committed() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            state.complete(
                    execution,
                    transcript()
            );

            // then
            assertThat(
                    state.find(recordingId)
                            .status()
            ).isEqualTo(TranscriptionStatus.SUCCEEDED);
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "completed"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                hasLogField(
                                        event,
                                        "event",
                                        "recording.transcription.persist"
                                )
                        ).isTrue();
                        assertThat(
                                hasLogField(
                                        event,
                                        "attemptId",
                                        execution.attemptId()
                                )
                        ).isTrue();
                    });
            assertThat(logs.events()).allSatisfy(
                    event -> assertThat(event.getFormattedMessage()).doesNotContain(
                            transcript().rawWhisperJson(),
                            execution.storageKey()
                    )
            );
        }
    }

    @Test
    @DisplayName("문서 접수 실패로 롤백하면 전사 저장 completed를 기록하지 않는다")
    void logging_complete_rollback() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        transactions.executeWithoutResult(
                status -> doThrow(new IllegalStateException("private-intake-body")).when(intake)
                        .acceptCompletedTranscript(
                                anyLong(),
                                anyLong(),
                                anyLong()
                        )
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> state.complete(
                            execution,
                            transcript()
                    )
            );

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(
                    state.find(recordingId)
                            .status()
            ).isEqualTo(TranscriptionStatus.PROCESSING);
            assertThat(count("transcripts")).isZero();
            assertThat(logs.events()).noneMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "completed"
                    )
            );
            assertThat(logs.events()).anyMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "failed"
                    )
            );
            assertThat(logs.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-intake-body"));
        }
    }

    @Test
    @DisplayName("AI의 정상 실패 결과도 실패 상태 커밋과 안전한 코드를 기록한다")
    void logging_fail_committed() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            state.fail(
                    execution,
                    "STT_FAILED"
            );

            // then
            assertThat(
                    state.find(recordingId)
                            .status()
            ).isEqualTo(TranscriptionStatus.FAILED);
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "failed"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                hasLogField(
                                        event,
                                        "errorCode",
                                        "STT_FAILED"
                                )
                        ).isTrue();
                        assertThat(
                                hasLogField(
                                        event,
                                        "attemptId",
                                        execution.attemptId()
                                )
                        ).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("재시도 뒤 이전 전사 결과는 ignored로 기록하며 저장 성공으로 세지 않는다")
    void logging_complete_staleIgnored() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution previous = state.findProcessing()
                .orElseThrow();
        state.fail(
                previous,
                "STT_FAILED"
        );
        state.retry(
                workspaceId,
                memberId,
                recordingId
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            state.complete(
                    previous,
                    transcript()
            );

            // then
            assertThat(
                    state.find(recordingId)
                            .status()
            ).isEqualTo(TranscriptionStatus.PROCESSING);
            assertThat(count("transcripts")).isZero();
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "ignored"
                    )
            )
                    .singleElement()
                    .satisfies(
                            event -> assertThat(
                                    hasLogField(
                                            event,
                                            "attemptId",
                                            previous.attemptId()
                                    )
                            ).isTrue()
                    );
            assertThat(logs.events()).noneMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "completed"
                    )
            );
        }
    }

    @Test
    @DisplayName("이미 완료된 전사의 늦은 실패 통지는 ignored로 기록하고 성공 상태를 유지한다")
    void logging_fail_nonProcessingIgnored() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        state.complete(
                execution,
                transcript()
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            state.fail(
                    execution,
                    "STT_FAILED"
            );

            // then
            assertThat(
                    state.find(recordingId)
                            .status()
            ).isEqualTo(TranscriptionStatus.SUCCEEDED);
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "ignored"
                    )
            )
                    .hasSize(1);
            assertThat(logs.events()).noneMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "failed"
                    )
            );
        }
    }

    @Test
    @DisplayName("재시도 예약은 새 attemptId가 커밋된 뒤 accepted로 기록한다")
    void logging_retry_newAttempt() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution previous = state.findProcessing()
                .orElseThrow();
        state.fail(
                previous,
                "STT_FAILED"
        );
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            RecordingTranscriptionRetryResult result = state.retry(
                    workspaceId,
                    memberId,
                    recordingId
            );

            // then
            assertThat(result.attemptId()).isNotEqualTo(previous.attemptId());
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "accepted"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                hasLogField(
                                        event,
                                        "event",
                                        "recording.transcription.retry_reserve"
                                )
                        ).isTrue();
                        assertThat(
                                hasLogField(
                                        event,
                                        "attemptId",
                                        result.attemptId()
                                )
                        ).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("전사 상태가 사라진 뒤 도착한 실패 통지는 ignored로 기록한다")
    void logging_fail_missingIgnored() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = state.findProcessing()
                .orElseThrow();
        jdbc.sql("DELETE FROM recording_transcriptions WHERE recording_session_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            state.fail(
                    execution,
                    "STT_FAILED"
            );

            // then
            assertThat(state.find(recordingId)).isNull();
            assertThat(logs.events()).filteredOn(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "ignored"
                    )
            )
                    .hasSize(1);
            assertThat(logs.events()).noneMatch(
                    event -> hasLogField(
                            event,
                            "outcome",
                            "failed"
                    )
            );
        }
    }

    private boolean hasLogField(
            ILoggingEvent event,
            String key,
            Object value
    ) {
        return event.getKeyValuePairs() != null && event.getKeyValuePairs()
                .stream()
                .anyMatch(
                        pair -> pair.key.equals(key) && String.valueOf(pair.value)
                                .equals(String.valueOf(value))
                );
    }

    private long completedRecording(
            long workspace,
            long member
    ) {
        long id = fixtures.saveRecording(
                workspace,
                member,
                30000
        );
        transactions.executeWithoutResult(status -> {
            RecordingAudioUpload upload = RecordingAudioUpload.reserve(
                    id,
                    "recordings/" + id + ".webm",
                    "audio/webm",
                    100,
                    NOW.minusSeconds(10)
            );
            upload.complete(
                    StoredAudioObject.of(
                            100,
                            "audio/webm",
                            "version-1"
                    ),
                    NOW.minusSeconds(5)
            );
            uploads.save(upload);
        });
        return id;
    }

    private void register(
            long workspace,
            long recordingId
    ) {
        transactions.executeWithoutResult(status -> {
            workspaces.findByIdForUpdate(workspace)
                    .orElseThrow();
            RecordingSession recording = recordings.findByIdForUpdate(recordingId)
                    .orElseThrow();
            state.registerAutomatic(
                    workspace,
                    recording.getId()
            );
        });
    }

    private void awaitAndRegister(
            CyclicBarrier barrier,
            long workspace,
            long recordingId
    ) {
        try {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            register(
                    workspace,
                    recordingId
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private AiTranscript transcript() {
        return new AiTranscript(
                1,
                "{\"transcription\":[{\"text\":\"회의 발언\"}]}",
                List.of(
                        new AiTranscript.Utterance(
                                0,
                                "speaker-A",
                                .125,
                                .8,
                                "첫 발언"
                        ),
                        new AiTranscript.Utterance(
                                1,
                                "speaker-B",
                                1,
                                1.5,
                                "다른 발언"
                        ),
                        new AiTranscript.Utterance(
                                2,
                                "speaker-A",
                                2,
                                2.5,
                                "이어진 발언"
                        )
                )
        );
    }
}
