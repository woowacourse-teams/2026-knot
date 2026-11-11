package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@IsolatedPostgresTest
@Import({RecordingTranscriptionWindowRepositoryAdapter.class})
@DataJpaTest
class RecordingTranscriptionWindowRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String PIPELINE = "incremental-v1";
    private static final String MANIFEST = "{\"schemaVersion\":1,\"chunks\":[]}";

    @Autowired
    private RecordingTranscriptionWindowRepository windows;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("구간 입력 계약과 시각을 영속화하고 구간 번호 순서로 조회한다")
    void save_success_roundTripAndOrdering() {
        // given
        long recordingId = saveRecording();
        windows.save(
                window(
                        recordingId,
                        1,
                        480000
                )
        );
        RecordingTranscriptionWindow first = windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );
        entityManager.clear();

        // when
        List<RecordingTranscriptionWindow> found = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                recordingId,
                PIPELINE
        );

        // then
        assertThat(found).extracting(RecordingTranscriptionWindow::getWindowIndex)
                .containsExactly(
                        0,
                        1
                );
        assertThat(
                found.getFirst()
                        .getManifestJson()
        ).isEqualTo(MANIFEST);
        assertThat(
                found.getFirst()
                        .getManifestSha256()
        ).hasSize(64);
        assertThat(
                found.getFirst()
                        .getAttemptId()
        ).isEqualTo(first.getAttemptId());
        assertThat(
                found.getFirst()
                        .getCreatedAt()
        ).isEqualTo(NOW);
        assertThat(
                windows.findByRecordingIdAndPipelineVersionAndWindowIndex(
                        recordingId,
                        "other",
                        0
                )
        ).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "window_index = 0, core_start_frame = 480000, core_end_frame = 960000, input_end_frame = 960000",
            "window_index = 1"})
    @DisplayName("같은 번호 또는 같은 시작 프레임의 충돌을 DB 고유 제약으로 차단한다")
    void update_failure_duplicateIdentity(String update) {
        // given
        long recordingId = saveRecording();
        windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );
        windows.save(
                window(
                        recordingId,
                        1,
                        480000
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient
                        .sql(
                                "UPDATE recording_transcription_windows SET " + update
                                        + " WHERE recording_id = :id AND window_index = 0"
                        )
                        .param(
                                "id",
                                recordingId
                        )
                        .update()
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core_end_frame = core_start_frame", "input_start_frame = -1",
            "input_end_frame = 115200001", "pipeline_version = ' '", "manifest_sha256 = 'invalid'",
            "manifest_json = 'invalid'", "manifest_json = repeat(' ', 1048576) || '{}'", "status = 'PROCESSING'",
            "claim_epoch = -1", "status = 'FAILED'", "status = 'SUCCEEDED'", "result_json = '{}'"})
    @DisplayName("입력 범위와 상태와 payload 계약을 DB에서도 검증한다")
    void update_failure_constraint(String update) {
        // given
        long recordingId = saveRecording();
        windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient
                        .sql("UPDATE recording_transcription_windows SET " + update + " WHERE recording_id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .update()
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("기한이 지난 대기 또는 임대 만료 작업만 제한 개수와 예약 순서대로 조회한다")
    void findReadyIds_success_dueAndExpiredLease() {
        // given
        long recordingId = saveRecording();
        RecordingTranscriptionWindow queued = windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );
        RecordingTranscriptionWindow expired = windows.save(
                window(
                        recordingId,
                        1,
                        480000
                )
        );
        RecordingTranscriptionWindow active = windows.save(
                window(
                        recordingId,
                        2,
                        960000
                )
        );
        RecordingTranscriptionWindow deadline = windows.save(
                window(
                        recordingId,
                        3,
                        1440000
                )
        );
        RecordingTranscriptionWindow future = windows.save(
                window(
                        recordingId,
                        4,
                        1920000
                )
        );
        update(
                """
                        status = 'PROCESSING', owner_token = gen_random_uuid(),
                        lease_until = next_run_at - interval '1 second'
                        """,
                expired.getId()
        );
        update(
                "next_run_at = next_run_at + interval '1 year'",
                active.getId()
        );
        update(
                "execution_deadline = next_run_at",
                deadline.getId()
        );
        update(
                "next_run_at = next_run_at + interval '1 year'",
                future.getId()
        );

        // when
        List<Long> found = windows.findReadyIds(
                NOW,
                2
        );

        // then
        assertThat(found).containsExactly(
                queued.getId(),
                expired.getId()
        );
    }

    @Test
    @DisplayName("전역에서 두 구간이 동시에 처리 중인 상태를 저장할 수 없다")
    void processing_failure_globalUniqueness() {
        // given
        long recordingId = saveRecording();
        RecordingTranscriptionWindow first = windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );
        RecordingTranscriptionWindow second = windows.save(
                window(
                        recordingId,
                        1,
                        480000
                )
        );
        update(
                "status='PROCESSING',owner_token=gen_random_uuid(),lease_until=next_run_at+interval '1 minute'",
                first.getId()
        );

        // when
        Throwable failure = catchThrowable(
                () -> update(
                        "status='PROCESSING',owner_token=gen_random_uuid(),lease_until=next_run_at+interval '1 minute'",
                        second.getId()
                )
        );

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("취소는 성공 결과와 실행 소유권을 제거하고 다른 녹음은 보존한다")
    void cancelAllByRecordingId_success_purgesResult() {
        // given
        long recordingId = saveRecording();
        long otherId = saveRecording();
        RecordingTranscriptionWindow succeeded = windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );
        RecordingTranscriptionWindow processing = windows.save(
                window(
                        recordingId,
                        1,
                        480000
                )
        );
        windows.save(
                window(
                        otherId,
                        0,
                        0
                )
        );
        update(
                "status = 'SUCCEEDED', result_schema_version = 1, result_json = '{}', result_sha256 = repeat('0', 64)",
                succeeded.getId()
        );
        update(
                "status = 'PROCESSING', owner_token = gen_random_uuid(), lease_until = now()",
                processing.getId()
        );

        // when
        windows.cancelAllByRecordingId(recordingId);

        // then
        List<RecordingTranscriptionWindow> cancelled = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                recordingId,
                PIPELINE
        );
        assertThat(cancelled).allSatisfy(value -> {
            assertThat(
                    value.getStatus()
                            .name()
            ).isEqualTo("CANCELLED");
            assertThat(value.getResultJson()).isNull();
            assertThat(value.getResultSha256()).isNull();
            assertThat(value.getResultSchemaVersion()).isNull();
            assertThat(value.getOwnerToken()).isNull();
            assertThat(value.getLeaseUntil()).isNull();
        });
        assertThat(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        otherId,
                        PIPELINE
                )
                        .getFirst()
                        .getStatus()
                        .name()
        ).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("존재하지 않는 녹음의 구간은 외래 키로 거절한다")
    void save_failure_missingRecording() {
        // given
        RecordingTranscriptionWindow value = window(
                Long.MAX_VALUE,
                0,
                0
        );

        // when
        Throwable thrown = catchThrowable(() -> windows.save(value));

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("녹음을 물리 삭제하면 구간도 함께 삭제한다")
    void delete_success_cascades() {
        // given
        long recordingId = saveRecording();
        windows.save(
                window(
                        recordingId,
                        0,
                        0
                )
        );

        // when
        jdbcClient.sql("DELETE FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();

        // then
        assertThat(
                windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                        recordingId,
                        PIPELINE
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("동시 생성은 고유 제약으로 같은 구간 하나만 저장한다")
    void save_success_concurrentUniqueInsert() throws Exception {
        // given
        TransactionTemplate transaction = independentTransaction();
        long recordingId = transaction.execute(status -> saveRecording());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Throwable> first = executor.submit(
                    () -> concurrentInsert(
                            transaction,
                            recordingId,
                            start
                    )
            );
            Future<Throwable> second = executor.submit(
                    () -> concurrentInsert(
                            transaction,
                            recordingId,
                            start
                    )
            );

            // when
            start.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            // then
            assertThat(outcomes).filteredOn(value -> value == null)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(value -> value != null)
                    .allSatisfy(value -> assertThat(value).isInstanceOf(DataIntegrityViolationException.class));
            assertThat(
                    windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                            recordingId,
                            PIPELINE
                    )
            ).hasSize(1);
        } finally {
            deleteRecording(
                    transaction,
                    recordingId
            );
        }
    }

    @Test
    @DisplayName("행 잠금은 재선점을 직렬화하고 이전 epoch의 완료를 거절한다")
    void findByIdForUpdate_success_serializesAndFencesStaleOwner() throws Exception {
        // given
        TransactionTemplate transaction = independentTransaction();
        long recordingId = transaction.execute(status -> saveRecording());
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        long windowId = transaction.execute(
                status -> windows.save(
                        window(
                                recordingId,
                                0,
                                0
                        )
                )
                        .getId()
        );
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                RecordingTranscriptionWindow value = windows.findByIdForUpdate(windowId)
                        .orElseThrow();
                assertThat(
                        value.claim(
                                firstOwner,
                                NOW,
                                Duration.ofSeconds(5),
                                Duration.ofMinutes(5)
                        )
                ).isTrue();
                windows.save(value);
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(
                    firstLocked.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<Boolean> second = executor.submit(() -> transaction.execute(status -> {
                secondStarted.countDown();
                RecordingTranscriptionWindow value = windows.findByIdForUpdate(windowId)
                        .orElseThrow();
                secondLocked.countDown();
                assertThat(
                        value.claim(
                                secondOwner,
                                NOW.plusSeconds(6),
                                Duration.ofSeconds(5),
                                Duration.ofMinutes(5)
                        )
                ).isTrue();
                boolean staleAccepted = value.succeed(
                        value.getAttemptId(),
                        firstOwner,
                        1,
                        NOW.plusSeconds(7),
                        1,
                        "{}"
                );
                windows.save(value);
                return staleAccepted;
            }));
            assertThat(
                    secondStarted.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            // when
            boolean enteredBeforeRelease = secondLocked.await(
                    200,
                    TimeUnit.MILLISECONDS
            );
            releaseFirst.countDown();
            first.get(
                    5,
                    TimeUnit.SECONDS
            );
            boolean staleAccepted = second.get(
                    5,
                    TimeUnit.SECONDS
            );

            // then
            assertThat(enteredBeforeRelease).isFalse();
            assertThat(staleAccepted).isFalse();
            RecordingTranscriptionWindow found = windows.findByRecordingIdAndPipelineVersionAndWindowIndex(
                    recordingId,
                    PIPELINE,
                    0
            )
                    .orElseThrow();
            assertThat(found.getClaimEpoch()).isEqualTo(2);
            assertThat(found.getOwnerToken()).isEqualTo(secondOwner);
            assertThat(found.getResultJson()).isNull();
        } finally {
            releaseFirst.countDown();
            deleteRecording(
                    transaction,
                    recordingId
            );
        }
    }

    private Throwable concurrentInsert(
            TransactionTemplate transaction,
            long recordingId,
            CountDownLatch start
    ) {
        await(start);
        return catchThrowable(
                () -> transaction.executeWithoutResult(
                        status -> windows.save(
                                window(
                                        recordingId,
                                        0,
                                        0
                                )
                        )
                )
        );
    }

    private void update(
            String assignment,
            long id
    ) {
        jdbcClient.sql("UPDATE recording_transcription_windows SET " + assignment + " WHERE id = :id")
                .param(
                        "id",
                        id
                )
                .update();
    }

    private RecordingTranscriptionWindow window(
            long recordingId,
            int index,
            long startFrame
    ) {
        return RecordingTranscriptionWindow.queued(
                recordingId,
                PIPELINE,
                index,
                startFrame,
                startFrame + 480000,
                startFrame,
                startFrame + 480000,
                MANIFEST,
                UUID.randomUUID(),
                NOW
        );
    }

    private long saveRecording() {
        long memberId = jdbcClient.sql("INSERT INTO members (nickname) VALUES ('window-member') RETURNING id")
                .query(Long.class)
                .single();
        long workspaceId = jdbcClient
                .sql("INSERT INTO workspaces (name, created_at) VALUES ('구간 팀', now()) RETURNING id")
                .query(Long.class)
                .single();
        RecordingSession recording = RecordingSession.start(
                workspaceId,
                memberId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0".repeat(64),
                NOW,
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
        entityManager.persist(recording);
        entityManager.flush();
        return recording.getId();
    }

    private TransactionTemplate independentTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void deleteRecording(
            TransactionTemplate transaction,
            long recordingId
    ) {
        transaction.executeWithoutResult(
                status -> jdbcClient.sql("DELETE FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .update()
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(
                    5,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException("동시 실행 검증 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "동시 실행 검증이 중단되었습니다",
                    exception
            );
        }
    }
}
