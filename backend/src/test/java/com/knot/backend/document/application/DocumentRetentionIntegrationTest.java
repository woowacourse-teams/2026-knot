package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.recording.application.RecordingAudioDeletionWorker;
import com.knot.backend.recording.application.RecordingAudioRetentionService;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.BrokenBarrierException;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.repository.DocumentRetentionRepository;
import com.knot.backend.recording.application.RecordingAudioDeletionStateService;
import com.knot.backend.recording.domain.repository.RecordingAudioDeletionRepository;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentRetentionIntegrationTest {
    private static final Instant CREATED = DocumentFixtures.CREATED_AT;
    private static final Instant EXPIRES = CREATED.plus(Duration.ofDays(7));

    @Autowired
    private DocumentRetentionCleanupService cleanup;
    @Autowired
    private RecordingAudioRetentionService audioRetention;
    @Autowired
    private RecordingAudioDeletionWorker deletion;
    @Autowired
    private RecordingAudioDeletionStateService deletionState;
    @Autowired
    private DocumentGenerationClaimService claims;
    @Autowired
    private DocumentGenerationResultService results;
    @Autowired
    private DocumentGenerationIntakeService intake;
    @Autowired
    private DocumentGenerationFailureService failures;
    @Autowired
    private DocumentRetentionRepository retentionRepository;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoSpyBean
    private RecordingAudioDeletionRepository audioRepository;
    @Autowired
    private DocumentGenerationJobRetryService retry;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    private DocumentFixtures fixtures;
    private long memberId;
    private long workspaceId;
    private long recordingId;
    private long transcriptId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(EXPIRES);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("녹음자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        transcriptId = fixtures.saveTranscript(recordingId);
        jdbc.sql("""
                INSERT INTO transcript_segments (transcript_id, position, start_millis, text)
                VALUES (:id, 0, 0, '실제 발언')
                """)
                .param(
                        "id",
                        transcriptId
                )
                .update();
    }

    @Test
    @DisplayName("만료 경계에 실패 Job과 원문을 정리하되 Batch 실패 이력은 유지한다")
    void cleanup_exactExpiryRetainsFailureHistory() {
        long jobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long batchId = batchId();
        when(clock.instant()).thenReturn(EXPIRES.minusNanos(1000));
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId
        );
        assertThat(count("document_generation_jobs")).isEqualTo(1);

        when(clock.instant()).thenReturn(EXPIRES);
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId
        );
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId
        );

        assertThat(count("document_generation_jobs")).isZero();
        assertThat(count("transcript_segments")).isZero();
        assertThat(count("transcripts")).isZero();
        assertThat(
                jdbc.sql("SELECT processing_status FROM document_generation_batches")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
        assertThat(
                jdbc.sql("SELECT failed_count FROM document_generation_batches")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT released_transcript_id FROM document_generation_batches")
                        .query(Long.class)
                        .single()
        ).isEqualTo(transcriptId);
        assertThatThrownBy(
                () -> retry.retry(
                        workspaceId,
                        memberId,
                        jobId
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("같은 원문을 사용하는 성공 문서가 있으면 실패 Job만 정리한다")
    void cleanup_successfulDocumentProtectsTranscript() {
        fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long succeeded = fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        String topic = jdbc.sql("SELECT topic FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        succeeded
                )
                .query(String.class)
                .single();
        fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                succeeded,
                topic
        );

        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId()
        );

        assertThat(count("document_generation_jobs")).isEqualTo(1);
        assertThat(count("documents")).isEqualTo(1);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 대기와 유효한 실패 Job의 입력은 정리하지 않는다")
    void cleanup_remainingJobsProtectTranscript() {
        fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        fixtures.saveJob(
                transcriptId,
                "FAILED",
                CREATED,
                CREATED.plusSeconds(60)
        );

        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId()
        );

        assertThat(count("document_generation_jobs")).isEqualTo(2);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(1);
    }

    @Test
    @DisplayName("내용 없음 결과는 입력 정리와 파일 삭제 접수를 한 트랜잭션으로 처리한다")
    void cleanup_noContentSchedulesAudioDeletion() {
        long batchId = fixtures.saveGenerationBatch(transcriptId);
        jdbc.sql("""
                UPDATE document_generation_batches SET topic_registration_state = 'NO_CONTENT',
                    processing_status = 'NO_CONTENT', cleanup_requested_at = registered_at, finished_at = registered_at
                WHERE id = :id
                """)
                .param(
                        "id",
                        batchId
                )
                .update();
        saveAudio(CREATED);

        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId
        );

        assertThat(count("transcripts")).isZero();
        assertThat(count("recording_audio_deletion_tasks")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT processing_status FROM document_generation_batches")
                        .query(String.class)
                        .single()
        ).isEqualTo("NO_CONTENT");
    }

    @Test
    @DisplayName("오디오는 일수로 삭제하지 않고 내용 없음 삭제의 저장소 장애를 재시도한다")
    void audioDeletion_retriesOutsideTransactionAndKeepsTranscript() {
        long uploadId = saveAudio(CREATED);
        Instant expired = CREATED.plus(Duration.ofDays(365));
        when(clock.instant()).thenReturn(expired);
        audioRetention.schedule(uploadId);
        assertThat(count("recording_audio_deletion_tasks")).isZero();
        markNoContent();
        audioRetention.schedule(uploadId);
        audioRetention.schedule(uploadId);
        assertThat(count("recording_audio_deletion_tasks")).isEqualTo(1);
        long taskId = jdbc.sql("SELECT id FROM recording_audio_deletion_tasks")
                .query(Long.class)
                .single();
        doThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE)).when(storage)
                .deleteStoredObject(anyString());

        deletion.execute(taskId);
        assertThat(
                jdbc.sql("SELECT status FROM recording_audio_deletion_tasks")
                        .query(String.class)
                        .single()
        ).isEqualTo("PENDING");
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                        .query(Integer.class)
                        .single()
        ).isZero();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage)
                .deleteStoredObject(anyString());
        when(clock.instant()).thenReturn(expired.plusSeconds(60));
        deletion.execute(taskId);
        deletion.execute(taskId);

        assertThat(
                jdbc.sql("SELECT status FROM recording_audio_deletion_tasks")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.sql("SELECT attempt_count FROM recording_audio_deletion_tasks")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
    }

    private void markNoContent() {
        long batch = fixtures.saveGenerationBatch(transcriptId);
        jdbc.sql("""
                UPDATE document_generation_batches SET topic_registration_state='NO_CONTENT',
                    processing_status='NO_CONTENT', cleanup_requested_at=registered_at, finished_at=registered_at,
                    queued_count=0,running_count=0,succeeded_count=0,failed_count=0 WHERE id=:id
                """)
                .param(
                        "id",
                        batch
                )
                .update();
    }

    @Test
    void previouslyScheduledAgeOnlyDeletionIsNotClaimedOrExecuted() {
        long upload = saveAudio(CREATED);
        long task = jdbc.sql("""
                INSERT INTO recording_audio_deletion_tasks
                    (upload_id,storage_key,status,requested_at,next_attempt_at)
                VALUES (:id,'recordings/test-key','PENDING',:at,:at) RETURNING id
                """)
                .param(
                        "id",
                        upload
                )
                .param(
                        "at",
                        Timestamp.from(CREATED)
                )
                .query(Long.class)
                .single();
        when(clock.instant()).thenReturn(CREATED.plus(Duration.ofDays(365)));
        assertThat(
                audioRepository.findReadyTasks(
                        clock.instant(),
                        10
                )
        ).isEmpty();
        assertThat(deletionState.claim(task)).isEmpty();
        deletion.execute(task);
        verify(
                storage,
                org.mockito.Mockito.never()
        ).deleteStoredObject(anyString());
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                        .query(Integer.class)
                        .single()
        ).isZero();
    }

    private long batchId() {
        return jdbc.sql("SELECT id FROM document_generation_batches")
                .query(Long.class)
                .single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Integer.class)
                .single();
    }

    private long saveAudio(Instant completedAt) {
        return jdbc.sql("""
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                VALUES (:recording, 'recordings/test-key', 'audio/webm', 100, 'COMPLETED', :time, :time) RETURNING id
                """)
                .param(
                        "recording",
                        recordingId
                )
                .param(
                        "time",
                        Timestamp.from(completedAt)
                )
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("분류 단계 실패도 만료 후 정리하고 동일 원문 통지는 재접수하지 않는다")
    void cleanup_classificationFailureRetainsIntakeIdentity() {
        when(clock.instant()).thenReturn(CREATED.plusSeconds(60));
        long classifier = transactions.execute(
                status -> intake.acceptCompletedTranscript(
                        workspaceId,
                        recordingId,
                        transcriptId
                )
        )
                .classificationJobId();
        claims.claim(
                workspaceId,
                classifier
        );
        failures.failAttempt(
                workspaceId,
                classifier,
                1,
                DocumentGenerationFailureCause.AUTHENTICATION
        );
        when(clock.instant()).thenReturn(EXPIRES.plusSeconds(60));

        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId()
        );

        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_jobs")).isZero();
        assertThat(
                transactions.execute(
                        status -> intake.acceptCompletedTranscript(
                                workspaceId,
                                recordingId,
                                transcriptId
                        )
                )
                        .classificationJobId()
        ).isNull();
        assertThat(count("document_generation_batches")).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 접수 저장 실패 시 구간·원문·Batch 참조 해제를 모두 롤백한다")
    void cleanup_failureRollsBackAllReferences() {
        long batchId = fixtures.saveGenerationBatch(transcriptId);
        jdbc.sql("""
                UPDATE document_generation_batches SET topic_registration_state = 'NO_CONTENT',
                    processing_status = 'NO_CONTENT', cleanup_requested_at = registered_at, finished_at = registered_at
                WHERE id = :id
                """)
                .param(
                        "id",
                        batchId
                )
                .update();
        saveAudio(CREATED);
        doThrow(new DataIntegrityViolationException("접수 저장 실패")).when(audioRepository)
                .saveAndFlush(any());

        assertThatThrownBy(
                () -> cleanup.cleanup(
                        workspaceId,
                        recordingId,
                        batchId
                )
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(1);
        assertThat(count("recording_audio_deletion_tasks")).isZero();
        assertThat(
                jdbc.sql("SELECT transcript_id FROM document_generation_batches")
                        .query(Long.class)
                        .single()
        ).isEqualTo(transcriptId);
    }

    @Test
    @DisplayName("재시도가 먼저 접수되면 정리가 기다린 후 QUEUED 입력을 보존한다")
    void cleanup_concurrentRetryFirstProtectsInput() throws Exception {
        long jobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long batch = batchId();
        when(clock.instant()).thenReturn(EXPIRES.minusSeconds(1));
        CountDownLatch retried = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> retrying = executor.submit(() -> transactions.executeWithoutResult(status -> {
                retry.retry(
                        workspaceId,
                        memberId,
                        jobId
                );
                retried.countDown();
                await(commit);
            }));
            assertThat(
                    retried.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            when(clock.instant()).thenReturn(EXPIRES);
            Future<?> cleaning = executor.submit(
                    () -> cleanup.cleanup(
                            workspaceId,
                            recordingId,
                            batch
                    )
            );
            try {
                assertThatThrownBy(
                        () -> cleaning.get(
                                200,
                                TimeUnit.MILLISECONDS
                        )
                ).isInstanceOf(TimeoutException.class);
            } finally {
                commit.countDown();
            }
            retrying.get(
                    5,
                    TimeUnit.SECONDS
            );
            cleaning.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
        assertThat(count("transcripts")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT status FROM document_generation_jobs")
                        .query(String.class)
                        .single()
        ).isEqualTo("QUEUED");
        assertThat(
                jdbc.sql("SELECT user_retry_count FROM document_generation_jobs")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("정리가 먼저 완료되면 동시 재시도는 삭제된 Job을 찾지 못한다")
    void cleanup_concurrentCleanupFirstRejectsRetry() throws Exception {
        long jobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long batch = batchId();
        CountDownLatch cleaned = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cleaning = executor.submit(() -> transactions.executeWithoutResult(status -> {
                cleanup.cleanup(
                        workspaceId,
                        recordingId,
                        batch
                );
                cleaned.countDown();
                await(commit);
            }));
            assertThat(
                    cleaned.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<?> retrying = executor.submit(
                    () -> retry.retry(
                            workspaceId,
                            memberId,
                            jobId
                    )
            );
            try {
                assertThatThrownBy(
                        () -> retrying.get(
                                200,
                                TimeUnit.MILLISECONDS
                        )
                ).isInstanceOf(TimeoutException.class);
            } finally {
                commit.countDown();
            }
            cleaning.get(
                    5,
                    TimeUnit.SECONDS
            );
            assertThatThrownBy(
                    () -> retrying.get(
                            5,
                            TimeUnit.SECONDS
                    )
            ).hasCauseInstanceOf(DocumentException.class);
        }
        assertThat(count("transcripts")).isZero();
        assertThat(count("document_generation_jobs")).isZero();
    }

    @Test
    @DisplayName("동시 삭제 접수와 claim은 각각 하나만 성공한다")
    void audioDeletion_concurrentSchedulingAndClaim() throws Exception {
        long upload = saveAudio(CREATED);
        markNoContent();
        when(clock.instant()).thenReturn(CREATED.plusSeconds(60));
        CyclicBarrier scheduling = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                await(scheduling);
                audioRetention.schedule(upload);
            });
            Future<?> second = executor.submit(() -> {
                await(scheduling);
                audioRetention.schedule(upload);
            });
            first.get(
                    5,
                    TimeUnit.SECONDS
            );
            second.get(
                    5,
                    TimeUnit.SECONDS
            );
            assertThat(count("recording_audio_deletion_tasks")).isEqualTo(1);
            long task = jdbc.sql("SELECT id FROM recording_audio_deletion_tasks")
                    .query(Long.class)
                    .single();
            CyclicBarrier claiming = new CyclicBarrier(2);
            Future<Boolean> one = executor.submit(() -> {
                await(claiming);
                return deletionState.claim(task)
                        .isPresent();
            });
            Future<Boolean> two = executor.submit(() -> {
                await(claiming);
                return deletionState.claim(task)
                        .isPresent();
            });
            assertThat(
                    one.get(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isNotEqualTo(
                    two.get(
                            5,
                            TimeUnit.SECONDS
                    )
            );
        }
        assertThat(
                jdbc.sql("SELECT attempt_count FROM recording_audio_deletion_tasks")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("파일 삭제 후 완료 기록 전에 중단돼도 lease 만료 후 다시 삭제한다")
    void audioDeletion_reclaimsExpiredLease() {
        long upload = saveAudio(CREATED);
        markNoContent();
        Instant expired = CREATED.plusSeconds(60);
        when(clock.instant()).thenReturn(expired);
        audioRetention.schedule(upload);
        long task = jdbc.sql("SELECT id FROM recording_audio_deletion_tasks")
                .query(Long.class)
                .single();
        assertThat(deletionState.claim(task)).isPresent();
        storage.deleteStoredObject("recordings/test-key");
        when(clock.instant()).thenReturn(expired.plusSeconds(120));

        deletion.execute(task);
        deletionState.succeed(
                task,
                1
        );

        assertThat(
                jdbc.sql("SELECT status FROM recording_audio_deletion_tasks")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.sql("SELECT attempt_count FROM recording_audio_deletion_tasks")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
        verify(
                storage,
                times(2)
        ).deleteStoredObject("recordings/test-key");
    }

    @Test
    @DisplayName("내용 없음 오디오는 첫 저장소 삭제가 성공하면 바로 삭제를 확정한다")
    void execute_success_noContentConfirmedOnFirstDeletion() {
        // given
        long batch = fixtures.saveGenerationBatch(transcriptId);
        jdbc.sql("""
                UPDATE document_generation_batches SET topic_registration_state = 'NO_CONTENT',
                    processing_status = 'NO_CONTENT', cleanup_requested_at = registered_at,
                    finished_at = registered_at
                WHERE id = :id
                """)
                .param(
                        "id",
                        batch
                )
                .update();
        saveAudio(CREATED);
        when(clock.instant()).thenReturn(CREATED.plusSeconds(60));
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batch
        );
        long task = jdbc.sql("SELECT id FROM recording_audio_deletion_tasks")
                .query(Long.class)
                .single();

        // when
        deletion.execute(task);

        // then
        assertThat(
                jdbc.sql("SELECT status FROM recording_audio_deletion_tasks")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        verify(storage).deleteStoredObject("recordings/test-key");
    }

    @Test
    @DisplayName("작업 실행과 결과 저장 이후 정리는 성공 원문을 보호한다")
    void cleanup_claimAndResultPreserveSuccessfulInput() {
        fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long queued = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        assertThat(
                claims.claim(
                        workspaceId,
                        queued
                )
        ).isPresent();
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId()
        );
        assertThat(count("transcripts")).isEqualTo(1);
        results.completeGeneration(
                workspaceId,
                queued,
                1,
                new DocumentGenerationResult(
                        "제목",
                        "요약",
                        "## 핵심 요약\n내용"
                )
        );
        cleanup.cleanup(
                workspaceId,
                recordingId,
                batchId()
        );
        assertThat(count("documents")).isEqualTo(1);
        assertThat(count("transcript_segments")).isEqualTo(1);
    }

    @Test
    @DisplayName("후보 조회는 문서 실패 만료·내용 없음과 삭제 lease를 구분하며 오래된 오디오는 보존한다")
    void candidates_selectOnlyEligibleRows() {
        fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        assertThat(
                retentionRepository.findCandidates(
                        EXPIRES.minusNanos(1000),
                        10
                )
        ).isEmpty();
        assertThat(
                retentionRepository.findCandidates(
                        EXPIRES,
                        10
                )
        ).hasSize(1);
        long upload = saveAudio(CREATED);
        when(clock.instant()).thenReturn(CREATED.plus(Duration.ofDays(365)));
        assertThat(audioRepository.findNoContentCandidates(10)).isEmpty();
        audioRetention.schedule(upload);
        assertThat(count("recording_audio_deletion_tasks")).isZero();
        markNoContent();
        assertThat(audioRepository.findNoContentCandidates(10)).containsExactly(upload);
        audioRetention.schedule(upload);
        long task = jdbc.sql("SELECT id FROM recording_audio_deletion_tasks")
                .query(Long.class)
                .single();
        assertThat(
                audioRepository.findReadyTasks(
                        clock.instant(),
                        10
                )
        ).containsExactly(task);
        deletionState.claim(task);
        assertThat(
                audioRepository.findReadyTasks(
                        clock.instant(),
                        10
                )
        ).isEmpty();
        assertThat(
                audioRepository.findReadyTasks(
                        clock.instant()
                                .plusSeconds(120),
                        10
                )
        ).containsExactly(task);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(
                    5,
                    TimeUnit.SECONDS
            )) {
                throw new AssertionError("트랜잭션 동기화 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new AssertionError(exception);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new AssertionError(exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new AssertionError(exception);
        }
    }
}
