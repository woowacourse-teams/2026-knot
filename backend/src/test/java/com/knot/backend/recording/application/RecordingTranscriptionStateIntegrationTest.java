package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.document.application.DocumentClassificationResultService;
import com.knot.backend.document.application.DocumentRetentionCleanupService;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicReference;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class RecordingTranscriptionStateIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);

    @Autowired
    private RecordingTranscriptionStateService recordingTranscriptionStateService;
    @Autowired
    private DocumentClassificationResultService classifications;
    @Autowired
    private DocumentRetentionCleanupService retention;
    @Autowired
    private DocumentGenerationJobRepository documentGenerationJobRepository;
    @Autowired
    private RecordingSessionRepository recordingSessionRepository;
    @Autowired
    private RecordingAudioUploadRepository recordingAudioUploadRepository;
    @Autowired
    private RecordingTranscriptionRepository recordingTranscriptionRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
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
        UUID attempt = recordingTranscriptionStateService.findSnapshot(recordingId)
                .attemptId();
        // when
        register(
                workspaceId,
                recordingId
        );
        // then
        assertThat(count("recording_transcriptions")).isEqualTo(1);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .attemptId()
        ).isEqualTo(attempt);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
    }

    @Test
    @DisplayName("원문·발화·문서 생성 접수와 전사 성공을 같은 트랜잭션에 저장한다")
    void complete_success_atomicAndIdempotent() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = currentExecution();
        AiTranscript result = transcript();
        // when
        recordingTranscriptionStateService.complete(
                execution,
                result
        );
        recordingTranscriptionStateService.complete(
                execution,
                result
        );
        // then
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(3);
        assertThat(count("document_generation_batches")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
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
        RecordingTranscriptionExecution execution = currentExecution();
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
                () -> recordingTranscriptionStateService.complete(
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
                recordingTranscriptionStateService.findSnapshot(recordingId)
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
        RecordingTranscriptionExecution previous = currentExecution();
        recordingTranscriptionStateService.fail(
                previous,
                "AI_TIMEOUT"
        );
        // when
        RecordingTranscriptionRetryResult retry = recordingTranscriptionStateService.retry(
                workspaceId,
                memberId,
                recordingId
        );
        recordingTranscriptionStateService.complete(
                previous,
                transcript()
        );
        recordingTranscriptionStateService.fail(
                previous,
                "LATE_FAILURE"
        );
        // then
        assertThat(retry.attemptId()).isNotEqualTo(previous.attemptId());
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .attemptId()
        ).isEqualTo(retry.attemptId());
        assertThat(count("transcripts")).isZero();
    }

    @Test
    @DisplayName("다른 워크스페이스의 동시 등록을 모두 대기열에 보존한다")
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
        ).containsExactly(
                "QUEUED",
                "QUEUED"
        );
    }

    @Test
    @DisplayName("대기 중인 작업은 새 시도로 재접수하지 않는다")
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
        UUID failedAttempt = recordingTranscriptionStateService.findSnapshot(nextRecording)
                .attemptId();
        // when
        Throwable failure = catchThrowable(
                () -> recordingTranscriptionStateService.retry(
                        workspaceId,
                        memberId,
                        nextRecording
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(nextRecording)
                        .attemptId()
        ).isEqualTo(failedAttempt);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(nextRecording)
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
    }

    @Test
    @DisplayName("성공한 작업은 재시도하지 않고 결과를 유지한다")
    void retry_failure_succeeded() {
        // given
        register(
                workspaceId,
                recordingId
        );
        recordingTranscriptionStateService.complete(
                currentExecution(),
                transcript()
        );
        // when
        Throwable failure = catchThrowable(
                () -> recordingTranscriptionStateService.retry(
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
        recordingTranscriptionStateService.fail(
                currentExecution(),
                "AI_TIMEOUT"
        );
        long otherMember = fixtures.saveMember("다른 회원");
        fixtures.join(
                workspaceId,
                otherMember
        );
        // when
        Throwable failure = catchThrowable(
                () -> recordingTranscriptionStateService.retry(
                        workspaceId,
                        otherMember,
                        recordingId
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
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
        RecordingTranscriptionExecution execution = currentExecution();
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
        recordingTranscriptionStateService.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_batches")).isZero();
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .failureCode()
        ).isEqualTo("RECORDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("재시작은 대기 작업과 시도 식별자를 보존한다")
    void recoverProcessing_success_explicitRetryOnly() {
        // given
        register(
                workspaceId,
                recordingId
        );
        UUID attempt = recordingTranscriptionStateService.findSnapshot(recordingId)
                .attemptId();
        // when
        register(
                workspaceId,
                recordingId
        );
        // then
        assertThat(recordingTranscriptionStateService.findProcessing()).isEmpty();
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .attemptId()
        ).isEqualTo(attempt);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .failureCode()
        ).isNull();
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
        currentExecution();
        long otherRecording = completedRecording(
                workspaceId,
                memberId
        );
        // when
        Throwable failure = catchThrowable(
                () -> transactions.executeWithoutResult(
                        status -> recordingTranscriptionRepository.saveAndFlush(
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
        RecordingTranscriptionExecution execution = currentExecution();
        jdbc.sql("UPDATE recording_sessions SET status = 'DISCARDED' WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        // when
        recordingTranscriptionStateService.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
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
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
    }

    @Test
    @DisplayName("내용 없음 원문 보존 기한 정리는 성공 상태를 유지하며 원문과 발화를 삭제한다")
    void complete_success_retentionReleasesTranscript() {
        // given
        register(
                workspaceId,
                recordingId
        );
        recordingTranscriptionStateService.complete(
                currentExecution(),
                transcript()
        );
        long jobId = jdbc.sql("SELECT id FROM document_generation_jobs")
                .query(Long.class)
                .single();
        long batchId = jdbc.sql("SELECT id FROM document_generation_batches")
                .query(Long.class)
                .single();
        transactions.executeWithoutResult(
                status -> documentGenerationJobRepository.findByWorkspaceIdAndIdForUpdate(
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
                recordingTranscriptionStateService.findSnapshot(recordingId)
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
        RecordingTranscriptionExecution execution = currentExecution();
        // when
        Throwable failure = catchThrowable(
                () -> recordingTranscriptionStateService.complete(
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
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
    }

    @Test
    @DisplayName("대기열은 접수 시각과 ID 순서로 한 건만 선점하고 앞 실패 후 다음을 실행한다")
    void claimNext_success_fifoAfterFailure() {
        // given
        long second = completedRecording(
                workspaceId,
                memberId
        );
        register(
                workspaceId,
                recordingId
        );
        register(
                workspaceId,
                second
        );
        UUID owner = UUID.randomUUID();
        // when
        RecordingTranscriptionExecution first = recordingTranscriptionStateService.claimNext(
                owner,
                java.time.Duration.ofSeconds(60)
        )
                .orElseThrow();
        // then
        assertThat(first.recordingId()).isEqualTo(recordingId);
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        UUID.randomUUID(),
                        java.time.Duration.ofSeconds(60)
                )
        ).isEmpty();
        recordingTranscriptionStateService.fail(
                first,
                "INFERENCE_FAILED"
        );
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        owner,
                        java.time.Duration.ofSeconds(60)
                )
                        .orElseThrow()
                        .recordingId()
        ).isEqualTo(second);
    }

    @Test
    @DisplayName("두 JVM의 동시 선점도 PostgreSQL admission 잠금 아래 실행 한 건만 만든다")
    void claimNext_success_concurrentOwners() throws Exception {
        // given
        long second = completedRecording(
                workspaceId,
                memberId
        );
        register(
                workspaceId,
                recordingId
        );
        register(
                workspaceId,
                second
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<Optional<RecordingTranscriptionExecution>> claim = () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            return recordingTranscriptionStateService.claimNext(
                    UUID.randomUUID(),
                    java.time.Duration.ofSeconds(60)
            );
        };
        // when
        List<Optional<RecordingTranscriptionExecution>> claimed;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<RecordingTranscriptionExecution>> first = executor.submit(claim);
            Future<Optional<RecordingTranscriptionExecution>> next = executor.submit(claim);
            claimed = List.of(
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    next.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
        // then
        assertThat(
                claimed.stream()
                        .filter(Optional::isPresent)
                        .count()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_transcriptions WHERE status = 'PROCESSING'")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_transcriptions WHERE status = 'QUEUED'")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("lease 만료 재선점은 같은 attempt의 epoch를 올리고 구 owner의 모든 변경을 거절한다")
    void claimNext_success_rejectsStaleOwner() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution old = currentExecution();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        RecordingTranscriptionExecution current = recordingTranscriptionStateService.claimNext(
                UUID.randomUUID(),
                java.time.Duration.ofSeconds(60)
        )
                .orElseThrow();
        // when
        recordingTranscriptionStateService.complete(
                old,
                transcript()
        );
        // then
        recordingTranscriptionStateService.fail(
                old,
                "LATE_FAILURE"
        );
        recordingTranscriptionStateService.requeue(
                old,
                java.time.Duration.ZERO
        );
        recordingTranscriptionStateService.observe(
                old,
                com.knot.backend.recording.domain.model.RemoteObservation.FAILED
        );
        assertThat(
                recordingTranscriptionStateService.renew(
                        old,
                        java.time.Duration.ofSeconds(60)
                )
        ).isFalse();
        assertThat(
                recordingTranscriptionStateService.beginRemotePost(
                        old,
                        java.time.Duration.ofSeconds(30)
                )
        ).isEmpty();
        assertThat(count("transcripts")).isZero();
        assertThat(current.attemptId()).isEqualTo(old.attemptId());
        assertThat(current.claimEpoch()).isEqualTo(old.claimEpoch() + 1);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
        recordingTranscriptionStateService.complete(
                current,
                transcript()
        );
        assertThat(count("transcripts")).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 시간은 실행 budget에 포함되지 않으며 응답 유실과 재선점은 deadline을 연장하지 않는다")
    void beginRemotePost_success_persistentDeadline() {
        // given
        register(
                workspaceId,
                recordingId
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        RecordingTranscriptionExecution first = currentExecution();
        Instant expected = NOW.plusSeconds(3630);
        // when
        Instant deadline = recordingTranscriptionStateService.beginRemotePost(
                first,
                java.time.Duration.ofSeconds(30)
        )
                .orElseThrow();
        // then
        assertThat(deadline).isEqualTo(expected);
        recordingTranscriptionStateService.requeue(
                first,
                java.time.Duration.ZERO
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(3601));
        RecordingTranscriptionExecution resumed = currentExecution();
        assertThat(resumed.executionDeadline()).isEqualTo(expected);
        assertThat(
                recordingTranscriptionStateService.beginRemotePost(
                        resumed,
                        java.time.Duration.ofHours(10)
                )
        ).contains(expected);
    }

    @Test
    @DisplayName("명시적 최초 거절은 실행 시계를 취소하지만 준비 대기 시각과 backoff를 보존한다")
    void deferReadiness_success_boundedBackoff() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution first = currentExecution();
        recordingTranscriptionStateService.beginRemotePost(
                first,
                java.time.Duration.ofSeconds(30)
        );
        // when
        recordingTranscriptionStateService.deferReadiness(
                first,
                java.time.Duration.ofSeconds(120),
                true
        );
        // then
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.QUEUED);
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        UUID.randomUUID(),
                        java.time.Duration.ofSeconds(60)
                )
        ).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        RecordingTranscriptionExecution second = currentExecution();
        assertThat(second.executionDeadline()).isNull();
        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        // 만료 lease는 같은 시도로 다시 선점해 준비 대기 상한을 판단한다.
        RecordingTranscriptionExecution last = recordingTranscriptionStateService.claimNext(
                UUID.randomUUID(),
                java.time.Duration.ofSeconds(60)
        )
                .orElseThrow();
        recordingTranscriptionStateService.deferReadiness(
                last,
                java.time.Duration.ofSeconds(120),
                true
        );
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .failureCode()
        ).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    @DisplayName("재시작은 다른 owner의 유효 lease를 초기화하지 않는다")
    void recoverProcessing_success_preservesLiveOwner() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution live = currentExecution();
        // when
        Optional<RecordingTranscriptionExecution> restarted = recordingTranscriptionStateService.claimNext(
                UUID.randomUUID(),
                java.time.Duration.ofSeconds(60)
        );
        // then
        assertThat(restarted).isEmpty();
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        UUID.randomUUID(),
                        java.time.Duration.ofSeconds(60)
                )
        ).isEmpty();
        assertThat(
                recordingTranscriptionStateService.findProcessing()
                        .orElseThrow()
                        .ownerToken()
        ).isEqualTo(live.ownerToken());
    }

    @Test
    @DisplayName("구 worker가 녹음 잠금을 기다리는 사이 새 owner가 선점해도 늦은 결과를 채택하지 않는다")
    void complete_success_staleOwnerAtCommitBarrier() throws Exception {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution old = currentExecution();
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                workspaceRepository.findByIdForUpdate(workspaceId)
                        .orElseThrow();
                locked.countDown();
                awaitLatch(release);
            }));
            assertThat(
                    locked.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<?> late = executor.submit(
                    () -> recordingTranscriptionStateService.complete(
                            old,
                            transcript()
                    )
            );
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
            // when
            RecordingTranscriptionExecution current = recordingTranscriptionStateService.claimNext(
                    UUID.randomUUID(),
                    java.time.Duration.ofSeconds(60)
            )
                    .orElseThrow();
            // then
            release.countDown();
            blocker.get(
                    10,
                    TimeUnit.SECONDS
            );
            late.get(
                    10,
                    TimeUnit.SECONDS
            );
            assertThat(count("transcripts")).isZero();
            assertThat(current.claimEpoch()).isEqualTo(old.claimEpoch() + 1);
            recordingTranscriptionStateService.complete(
                    current,
                    transcript()
            );
            assertThat(count("transcripts")).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("lease 갱신 후에는 이전 만료 시각이 지나도 다른 owner가 선점하지 않는다")
    void renew_success_extendsOnlyCurrentLease() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution current = currentExecution();
        when(clock.instant()).thenReturn(NOW.plusSeconds(59));
        // when
        boolean renewed = recordingTranscriptionStateService.renew(
                current,
                java.time.Duration.ofSeconds(60)
        );
        // then
        assertThat(renewed).isTrue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        UUID.randomUUID(),
                        java.time.Duration.ofSeconds(60)
                )
        ).isEmpty();
    }

    private void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException("검증 잠금 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "검증 스레드가 중단되었습니다",
                    exception
            );
        }
    }

    @Test
    @DisplayName("종료 후 시작자가 공유 워크스페이스에서 탈퇴하면 늦은 전사와 문서를 채택하지 않는다")
    void complete_success_creatorLeftWorkspace() {
        // given
        long remaining = fixtures.saveMember("남은 회원");
        fixtures.join(
                workspaceId,
                remaining
        );
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = currentExecution();
        jdbc.sql("UPDATE workspace_members SET left_at = :now WHERE workspace_id = :workspace AND member_id = :member")
                .param(
                        "now",
                        java.sql.Timestamp.from(NOW)
                )
                .param(
                        "workspace",
                        workspaceId
                )
                .param(
                        "member",
                        memberId
                )
                .update();
        // when
        recordingTranscriptionStateService.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_jobs")).isZero();
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .failureCode()
        ).isEqualTo("RECORDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("원본 정리 배치 전에도 30일이 지난 음원은 전사나 문서로 채택하지 않는다")
    void complete_success_retentionExpiredBeforeCleanup() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution execution = currentExecution();
        jdbc.sql(
                "UPDATE recording_audio_uploads SET completed_at = :expired, reserved_at = :expired "
                        + "WHERE recording_id = :id"
        )
                .param(
                        "expired",
                        java.sql.Timestamp.from(NOW.minusSeconds(30L * 86400))
                )
                .param(
                        "id",
                        recordingId
                )
                .update();
        // when
        recordingTranscriptionStateService.complete(
                execution,
                transcript()
        );
        // then
        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_jobs")).isZero();
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .failureCode()
        ).isEqualTo("RECORDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("미확정 시도의 확인된 성공은 다른 녹음 실행 중에도 같은 결과를 원자적으로 채택한다")
    void adoptUncertainResult_success_preservesOtherProcessing() {
        // given
        register(
                workspaceId,
                recordingId
        );
        RecordingTranscriptionExecution previous = currentExecution();
        recordingTranscriptionStateService.fail(
                previous,
                "AI_OUTCOME_UNKNOWN"
        );
        long second = completedRecording(
                workspaceId,
                memberId
        );
        register(
                workspaceId,
                second
        );
        RecordingTranscriptionExecution other = currentExecution();
        // when
        RecordingTranscriptionRetryResult recovered = recordingTranscriptionStateService.adoptUncertainResult(
                workspaceId,
                memberId,
                previous,
                transcript()
        );
        // then
        assertThat(recovered.attemptId()).isEqualTo(previous.attemptId());
        assertThat(recovered.status()).isEqualTo(TranscriptionStatus.SUCCEEDED);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(second)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertThat(
                recordingTranscriptionStateService.findProcessing()
                        .orElseThrow()
                        .ownerToken()
        ).isEqualTo(other.ownerToken());
    }

    @Test
    @DisplayName("업로드 채택 커밋 콜백의 전사 선점도 독립 트랜잭션에 확정되고 다른 owner를 막는다")
    void claimNext_success_commitsFromAfterCommitCallback() {
        // given
        UUID owner = UUID.randomUUID();
        AtomicReference<RecordingTranscriptionExecution> claimed = new AtomicReference<>();
        // when
        transactions.executeWithoutResult(status -> {
            workspaceRepository.findByIdForUpdate(workspaceId)
                    .orElseThrow();
            recordingSessionRepository.findByIdForUpdate(recordingId)
                    .orElseThrow();
            recordingTranscriptionStateService.registerAutomatic(recordingId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    claimed.set(
                            recordingTranscriptionStateService.claimNext(
                                    owner,
                                    java.time.Duration.ofSeconds(60)
                            )
                                    .orElseThrow()
                    );
                }
            });
        });
        // then
        assertThat(claimed.get()).isNotNull();
        RecordingTranscriptionExecution persisted = transactions.execute(
                status -> recordingTranscriptionStateService.findProcessing()
                        .orElseThrow()
        );
        assertThat(persisted.ownerToken()).isEqualTo(owner);
        assertThat(persisted.claimEpoch()).isEqualTo(
                claimed.get()
                        .claimEpoch()
        );
        assertThat(
                recordingTranscriptionStateService.findSnapshot(recordingId)
                        .status()
        ).isEqualTo(TranscriptionStatus.PROCESSING);
        assertThat(
                recordingTranscriptionStateService.claimNext(
                        UUID.randomUUID(),
                        java.time.Duration.ofSeconds(60)
                )
        ).isEmpty();
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
            recordingAudioUploadRepository.save(upload);
        });
        return id;
    }

    private void register(
            long workspace,
            long recordingId
    ) {
        transactions.executeWithoutResult(status -> {
            workspaceRepository.findByIdForUpdate(workspace)
                    .orElseThrow();
            RecordingSession recording = recordingSessionRepository.findByIdForUpdate(recordingId)
                    .orElseThrow();
            recordingTranscriptionStateService.registerAutomatic(recording.getId());
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
    private RecordingTranscriptionExecution currentExecution() {
        return recordingTranscriptionStateService.findProcessing()
                .orElseGet(
                        () -> recordingTranscriptionStateService.claimNext(
                                UUID.randomUUID(),
                                java.time.Duration.ofSeconds(60)
                        )
                                .orElseThrow()
                );
    }

}
