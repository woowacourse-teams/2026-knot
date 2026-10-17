package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AudioFinalizationClaim;
import com.knot.backend.recording.domain.AudioFinalizationLeaseLostException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class RecordingAudioCleanupIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    @Autowired
    private RecordingAudioCleanupStateService state;
    @Autowired
    private RecordingAudioCleanupRepository repository;
    @Autowired
    private RecordingAudioDeletionWorker worker;
    @Autowired
    private RecordingTranscriptionStateService transcriptionState;
    @Autowired
    private RecordingAudioFinalizationTransaction finalization;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    @MockitoBean
    private RecordingAudioFinalizationWorker finalizer;
    @MockitoBean
    private RecordingTranscriptionService transcriptions;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("""
                TRUNCATE TABLE recording_audio_cleanup, recording_sessions, workspace_members,
                    workspaces, oauth_identities, members RESTART IDENTITY CASCADE
                """)
                .update();
        jdbc.sql("INSERT INTO members (nickname) VALUES ('정리')")
                .update();
        jdbc.sql("INSERT INTO workspaces (name, created_at) VALUES ('정리', now())")
                .update();
        jdbc.sql("""
                INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
        jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,
                    control_token_hash,status,audio_mode,started_at,last_seen_at,ended_at,end_reason)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),'ENDED',
                    'PCM16_CHUNKS_V1',CAST(:now AS TIMESTAMPTZ)-INTERVAL '10 seconds',
                    CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),'USER_ENDED')
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_streams(recording_id,next_sequence,total_frame_count,
                    seal_kind,sealed_at,accept_until,tail_unknown)
                VALUES (1,2,1,'NORMAL',CAST(:now AS TIMESTAMPTZ),
                    CAST(:now AS TIMESTAMPTZ)+INTERVAL '120 seconds',FALSE)
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_chunks(recording_id,sequence,sample_offset,frame_count,
                    byte_length,checksum_sha256,storage_key,status,upload_url_expires_at)
                VALUES (1,1,0,1,2,'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
                    'recordings/1/chunks/1','RESERVED',CAST(:now AS TIMESTAMPTZ)+INTERVAL '300 seconds')
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_finalizations(recording_id,status,created_at,next_run_at)
                VALUES (1,'QUEUED',CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ))
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> repository.track(
                        1,
                        NOW
                )
        );
    }

    @AfterEach
    void clearIndependentTombstones() {
        jdbc.sql("TRUNCATE TABLE recording_audio_cleanup")
                .update();
    }

    @Test
    void failedFinalizationPreservesRawUntilRecoveryLimit() {
        // given
        jdbc.sql("UPDATE recording_audio_finalizations SET status='FAILED',retryable=TRUE")
                .update();
        // when
        state.schedule(1);
        // then
        assertThat(scope()).isEqualTo("DORMANT");
        assertThat(state.claim(1)).isEmpty();
        assertThat(
                column(
                        "frozen",
                        Boolean.class,
                        "recording_audio_streams"
                )
        ).isFalse();
    }

    @Test
    void adoptedFinalizationDeletesOnlyRawAndPreservesMaximumUrlDeadline() {
        // given
        ready();
        // when
        state.schedule(1);
        // then
        RecordingAudioCleanupStateService.CleanupExecution claim = state.claim(1)
                .orElseThrow();
        assertThat(scope()).isEqualTo("RAW");
        assertThat(claim.storagePrefix()).isEqualTo("recordings/1/chunks/");
        assertThat(
                column(
                        "max_url_expires_at",
                        Instant.class,
                        "recording_audio_cleanup"
                )
        ).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void failedRetentionClosesAtExactLogicalThirtyDays() {
        // given
        when(clock.instant()).thenReturn(
                NOW.plus(Duration.ofDays(30))
                        .minusNanos(1000)
        );
        state.schedule(1);
        assertThat(scope()).isEqualTo("DORMANT");
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(30)));
        // when
        state.schedule(1);
        // then
        assertThat(scope()).isEqualTo("ALL");
        assertThat(
                column(
                        "frozen",
                        Boolean.class,
                        "recording_audio_streams"
                )
        ).isTrue();
        assertThat(
                column(
                        "status",
                        String.class,
                        "recording_audio_finalizations"
                )
        ).isEqualTo("DISCARDED");
        assertThat(
                column(
                        "retryable",
                        Boolean.class,
                        "recording_audio_finalizations"
                )
        ).isFalse();
    }

    @Test
    void readyRetentionUsesCompletionTimeAndRetainsHistoricalQuality() {
        // given
        ready();
        completedUpload(NOW.plusSeconds(10));
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(30)));
        state.schedule(1);
        assertThat(scope()).isEqualTo("RAW");
        when(clock.instant()).thenReturn(
                NOW.plusSeconds(10)
                        .plus(Duration.ofDays(30))
        );
        // when
        state.schedule(1);
        // then
        assertThat(scope()).isEqualTo("ALL");
        assertThat(
                column(
                        "status",
                        String.class,
                        "recording_audio_finalizations"
                )
        ).isEqualTo("READY");
        assertThat(
                column(
                        "quality",
                        String.class,
                        "recording_audio_finalizations"
                )
        ).isEqualTo("PARTIAL");
    }

    @Test
    void departureClosesJobAndStreamInSameTransaction() {
        // given
        jdbc.sql("UPDATE workspace_members SET left_at=now()")
                .update();
        // when
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> state.afterDeparture(
                        1,
                        1
                )
        );
        // then
        assertThat(scope()).isEqualTo("ALL");
        assertThat(
                column(
                        "status",
                        String.class,
                        "recording_audio_finalizations"
                )
        ).isEqualTo("DISCARDED");
        assertThat(
                column(
                        "frozen",
                        Boolean.class,
                        "recording_audio_streams"
                )
        ).isTrue();
    }

    @Test
    void independentIntentSurvivesPhysicalParentDeletion() {
        // given
        jdbc.sql("DELETE FROM recording_audio_finalizations")
                .update();
        jdbc.sql("DELETE FROM recording_audio_chunks")
                .update();
        jdbc.sql("DELETE FROM recording_audio_streams")
                .update();
        jdbc.sql("DELETE FROM recording_sessions")
                .update();
        jdbc.sql("DELETE FROM workspace_members")
                .update();
        jdbc.sql("DELETE FROM workspaces")
                .update();
        // when
        state.schedule(1);
        // then
        assertThat(scope()).isEqualTo("ALL");
        assertThat(
                state.claim(1)
                        .orElseThrow()
                        .storagePrefix()
        ).isEqualTo("recordings/1/");
    }

    @Test
    void deleteFailureRemainsPendingAndLatePutIsSweptAgain() {
        // given
        jdbc.sql("UPDATE workspace_members SET left_at=now()")
                .update();
        state.schedule(1);
        doThrow(new IllegalStateException("저장소 장애")).doNothing()
                .when(storage)
                .deleteRecordingPrefix(anyString());
        worker.executeCleanup(1);
        assertThat(
                column(
                        "last_failure_code",
                        String.class,
                        "recording_audio_cleanup"
                )
        ).isEqualTo("AUDIO_STORAGE_UNAVAILABLE");
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        worker.executeCleanup(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        worker.executeCleanup(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(180));
        worker.executeCleanup(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(240));
        worker.executeCleanup(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        worker.executeCleanup(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3900));
        // when
        worker.executeCleanup(1);
        // then
        verify(
                storage,
                times(7)
        ).deleteRecordingPrefix("recordings/1/");
        assertThat(
                column(
                        "last_failure_code",
                        String.class,
                        "recording_audio_cleanup"
                )
        ).isNull();
        assertThat(
                column(
                        "next_attempt_at",
                        Instant.class,
                        "recording_audio_cleanup"
                )
        ).isEqualTo(NOW.plusSeconds(7500));
    }

    @Test
    void cleanupClaimHasOneOwnerAcrossConcurrentDatabaseTransactions() throws Exception {
        // given
        ready();
        state.schedule(1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<RecordingAudioCleanupStateService.CleanupExecution>> first = executor.submit(() -> {
                barrier.await();
                return state.claim(1);
            });
            Future<Optional<RecordingAudioCleanupStateService.CleanupExecution>> second = executor.submit(() -> {
                barrier.await();
                return state.claim(1);
            });
            // when
            Optional<RecordingAudioCleanupStateService.CleanupExecution> firstClaim = first.get(
                    10,
                    TimeUnit.SECONDS
            );
            // then
            int owners = firstClaim.stream()
                    .toList()
                    .size()
                    + second.get(
                            10,
                            TimeUnit.SECONDS
                    )
                            .stream()
                            .toList()
                            .size();
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test
    void departureCommitRejectsFinalizerWaitingOnWorkspaceLock() throws Exception {
        // given
        AudioFinalizationClaim claim = finalization.claim(1)
                .orElseThrow();
        StoredAudioChunk chunk = new StoredAudioChunk(
                true,
                2,
                "application/octet-stream",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "chunk-v1"
        );
        assertThat(
                finalization.reconcile(
                        claim,
                        Map.of(
                                1,
                                chunk
                        ),
                        NOW
                )
        ).isPresent();
        StoredAudioChunk wav = new StoredAudioChunk(
                true,
                46,
                "audio/wav",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "final-v1"
        );
        CountDownLatch cleanupReady = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch adoptionStarted = new CountDownLatch(1);
        AtomicInteger adoptionPid = new AtomicInteger();
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> departure = executor
                        .submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            jdbc.sql("SELECT id FROM workspaces WHERE id=1 FOR UPDATE")
                                    .query(Long.class)
                                    .single();
                            jdbc.sql("UPDATE workspace_members SET left_at=now()")
                                    .update();
                            state.afterDeparture(
                                    1,
                                    1
                            );
                            cleanupReady.countDown();
                            awaitBarrier(releaseCleanup);
                        }));
                awaitBarrier(cleanupReady);
                Future<Throwable> adoption = executor.submit(
                        () -> catchThrowable(
                                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                                    adoptionPid.set(
                                            jdbc.sql("SELECT pg_backend_pid()")
                                                    .query(Integer.class)
                                                    .single()
                                    );
                                    adoptionStarted.countDown();
                                    finalization.adopt(
                                            claim,
                                            wav
                                    );
                                })
                        )
                );
                awaitBarrier(adoptionStarted);
                awaitDatabaseLock(adoptionPid.get());
                assertThat(adoption.isDone()).isFalse();
                // when
                releaseCleanup.countDown();
                // then
                departure.get(
                        10,
                        TimeUnit.SECONDS
                );
                assertThat(
                        adoption.get(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isInstanceOf(AudioFinalizationLeaseLostException.class);
                assertThat(scope()).isEqualTo("ALL");
                assertThat(
                        column(
                                "status",
                                String.class,
                                "recording_audio_finalizations"
                        )
                ).isEqualTo("DISCARDED");
                assertThat(
                        jdbc.sql("SELECT count(*) FROM recording_audio_uploads")
                                .query(Integer.class)
                                .single()
                ).isZero();
                assertThat(
                        jdbc.sql("SELECT count(*) FROM recording_transcriptions")
                                .query(Integer.class)
                                .single()
                ).isZero();
            } finally {
                releaseCleanup.countDown();
            }
        }
    }

    @Test
    void scopePromotionInvalidatesOldRawDeletionAcknowledgement() {
        // given
        ready();
        state.schedule(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.sql("UPDATE workspace_members SET left_at=now()")
                    .update();
            state.schedule(1);
            return null;
        }).when(storage)
                .deleteRecordingPrefix("recordings/1/chunks/");
        // when
        worker.executeCleanup(1);
        // then
        assertThat(scope()).isEqualTo("ALL");
        assertThat(
                column(
                        "last_swept_at",
                        Instant.class,
                        "recording_audio_cleanup"
                )
        ).isNull();
        assertThat(
                state.claim(1)
                        .orElseThrow()
                        .storagePrefix()
        ).isEqualTo("recordings/1/");
    }

    @Test
    void noContentFailureSchedulesAllImmediately() {
        // given
        jdbc.sql("UPDATE recording_audio_finalizations SET status='FAILED',failure_code='AUDIO_NO_CONTENT'")
                .update();
        // when
        state.schedule(1);
        // then
        assertThat(scope()).isEqualTo("ALL");
    }

    @Test
    void leavingAndRejoiningCannotAdoptLateAiResult() {
        // given
        RecordingTranscriptionExecution execution = queuedExecution();
        assertThat(transcriptionState.prepare(execution)).isPresent();
        departAndRejoin();
        AiTranscript result = new AiTranscript(
                1,
                "{}",
                List.of(
                        new AiTranscript.Utterance(
                                0,
                                "A",
                                0,
                                0.001,
                                "발화"
                        )
                )
        );
        // when
        transcriptionState.complete(
                execution,
                result
        );
        // then
        assertThat(
                jdbc.sql("SELECT count(*) FROM transcripts")
                        .query(Integer.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT count(*) FROM document_generation_batches")
                        .query(Integer.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
        assertThat(
                column(
                        "revoked_at",
                        Instant.class,
                        "recording_audio_uploads"
                )
        ).isEqualTo(NOW);
        assertThat(
                column(
                        "deleted_at",
                        Instant.class,
                        "recording_audio_uploads"
                )
        ).isNull();
        assertThat(
                column(
                        "quality",
                        String.class,
                        "recording_audio_finalizations"
                )
        ).isEqualTo("PARTIAL");
    }

    @Test
    void leavingAndRejoiningPermanentlyRejectsTranscriptionRetry() {
        // given
        RecordingTranscriptionExecution execution = queuedExecution();
        transcriptionState.fail(
                execution,
                "INFERENCE_FAILED"
        );
        departAndRejoin();
        // when
        Throwable failure = catchThrowable(
                () -> transcriptionState.retry(
                        1,
                        1,
                        1
                )
        );
        // then
        assertThat(failure).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    @Test
    void leavingAndRejoiningPermanentlyRejectsPreparedRemotePost() {
        // given
        RecordingTranscriptionExecution execution = queuedExecution();
        departAndRejoin();
        // when
        Optional<RecordingTranscriptionExecution> prepared = transcriptionState.prepare(execution);
        // then
        assertThat(prepared).isEmpty();
        assertThat(
                column(
                        "revoked_at",
                        Instant.class,
                        "recording_audio_uploads"
                )
        ).isEqualTo(NOW);
    }

    @Test
    void leavingAndRejoiningRejectsUncertainRetryBeforeRemoteLookup() {
        // given
        queuedExecution();
        departAndRejoin();
        // when
        Throwable failure = catchThrowable(
                () -> transcriptionState.findUncertainRetry(
                        1,
                        1,
                        1
                )
        );
        // then
        assertThat(failure).isInstanceOf(com.knot.backend.recording.domain.RecordingException.class);
    }

    private void awaitBarrier(CountDownLatch barrier) {
        try {
            assertThat(
                    barrier.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "경합 테스트 대기 중 인터럽트가 발생했습니다.",
                    failure
            );
        }
    }

    private void awaitDatabaseLock(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean blocked = jdbc.sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                    .param(
                            "pid",
                            pid
                    )
                    .query(Boolean.class)
                    .single();
            if (blocked) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("최종 WAV 채택 트랜잭션이 PostgreSQL 잠금에서 대기하지 않았습니다.");
    }

    private RecordingTranscriptionExecution queuedExecution() {
        ready();
        completedUpload(NOW);
        new TransactionTemplate(transactions).executeWithoutResult(status -> transcriptionState.registerAutomatic(1));
        return transcriptionState.claimNext(
                UUID.randomUUID(),
                Duration.ofMinutes(1)
        )
                .orElseThrow();
    }

    private void departAndRejoin() {
        jdbc.sql("UPDATE workspace_members SET left_at=now()")
                .update();
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> state.afterDeparture(
                        1,
                        1
                )
        );
        jdbc.sql("UPDATE workspace_members SET left_at=NULL")
                .update();
    }

    private String scope() {
        return column(
                "scope",
                String.class,
                "recording_audio_cleanup"
        );
    }

    private <T> T column(
            String name,
            Class<T> type,
            String table
    ) {
        return jdbc.sql("SELECT " + name + " FROM " + table + " WHERE recording_id=1")
                .query(type)
                .optional()
                .orElse(null);
    }

    private void ready() {
        jdbc.sql("""
                UPDATE recording_audio_finalizations SET status='READY',quality='PARTIAL',manifest_json='[]',
                    manifest_hash='manifest',final_key='recordings/1/final/1.wav',final_version_id='version',
                    final_checksum='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',final_byte_length=46,
                    ready_at=CAST(:now AS TIMESTAMPTZ)
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
    }

    private void completedUpload(Instant completedAt) {
        jdbc.sql("""
                INSERT INTO recording_audio_uploads(recording_id,storage_key,content_type,content_length,
                    status,reserved_at,completed_at,storage_version_id)
                VALUES (1,'recordings/1/final/1.wav','audio/wav',46,'COMPLETED',CAST(:now AS TIMESTAMPTZ),
                    CAST(:completed AS TIMESTAMPTZ),'ready-v1')
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .param(
                        "completed",
                        completedAt.toString()
                )
                .update();
    }
}
