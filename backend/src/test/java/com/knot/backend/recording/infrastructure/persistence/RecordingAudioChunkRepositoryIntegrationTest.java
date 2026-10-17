package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@Import({TestcontainersConfiguration.class, RecordingAudioChunkRepositoryAdapter.class,
        RecordingAudioStreamRepositoryAdapter.class})
@DataJpaTest
class RecordingAudioChunkRepositoryIntegrationTest {
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Autowired
    private RecordingAudioChunkRepository chunks;
    @Autowired
    private RecordingAudioStreamRepository streams;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("PCM 세션과 stream과 검증 완료 조각의 버전과 시각을 실제 DB에서 왕복한다")
    void save_success_mapping() {
        // given
        long recordingId = saveRecording();
        RecordingAudioStream stream = RecordingAudioStream.create(recordingId);
        stream.reserve(descriptor(1));
        streams.save(stream);
        RecordingAudioChunk chunk = chunk(
                recordingId,
                1
        );
        chunk.verify(
                new StoredAudioChunk(
                        true,
                        160000,
                        "application/octet-stream",
                        HASH,
                        "version-1"
                ),
                NOW
        );
        chunks.save(chunk);
        entityManager.clear();

        // when
        RecordingAudioChunk found = chunks.findByRecordingIdAndSequence(
                recordingId,
                1
        )
                .orElseThrow();

        // then
        assertThat(found.getStatus()).isEqualTo(RecordingAudioChunkStatus.VERIFIED);
        assertThat(found.getStorageVersionId()).isEqualTo("version-1");
        assertThat(found.getChecksumSha256()).isEqualTo(HASH);
        assertThat(found.getVerifiedAt()).isEqualTo(NOW);
        assertThat(found.getUploadUrlExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(
                streams.findByRecordingIdForUpdate(recordingId)
                        .orElseThrow()
                        .getTotalFrameCount()
        ).isEqualTo(80000);
        assertThat(
                entityManager.find(
                        RecordingSession.class,
                        recordingId
                )
                        .getAudioMode()
        ).isEqualTo(RecordingAudioMode.PCM16_CHUNKS_V1);
    }

    @Test
    @DisplayName("같은 녹음의 같은 번호 조각을 중복 저장할 수 없다")
    void save_failure_duplicateSequence() {
        // given
        long recordingId = saveRecording();
        chunks.save(
                chunk(
                        recordingId,
                        1
                )
        );
        RecordingAudioChunk duplicate = RecordingAudioChunk.reserve(
                recordingId,
                descriptor(1),
                "other-key",
                NOW.plusSeconds(60)
        );

        // when
        Throwable thrown = catchThrowable(() -> chunks.save(duplicate));

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 녹음의 stream을 중복 저장할 수 없다")
    void save_failure_duplicateStream() {
        // given
        long recordingId = saveRecording();
        streams.save(RecordingAudioStream.create(recordingId));

        // when
        Throwable thrown = catchThrowable(() -> streams.save(RecordingAudioStream.create(recordingId)));

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 녹음의 조각은 외래 키로 거절한다")
    void save_failure_missingRecording() {
        // given
        RecordingAudioChunk chunk = chunk(
                Long.MAX_VALUE,
                1
        );

        // when
        Throwable thrown = catchThrowable(() -> chunks.save(chunk));

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("조각 메타데이터가 남아 있으면 녹음 삭제를 제한해 저장 키를 보존한다")
    void delete_failure_preserveCleanupMetadata() {
        // given
        long recordingId = saveRecording();
        chunks.save(
                chunk(
                        recordingId,
                        1
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient.sql("DELETE FROM recording_sessions WHERE id = :id")
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
    @DisplayName("조각 조회는 저장 순서와 관계없이 sequence 순서로 반환한다")
    void findAllByRecordingIdOrderBySequence_success() {
        // given
        long recordingId = saveRecording();
        chunks.save(
                chunk(
                        recordingId,
                        2
                )
        );
        chunks.save(
                chunk(
                        recordingId,
                        1
                )
        );

        // when
        List<RecordingAudioChunk> found = chunks.findAllByRecordingIdOrderBySequence(recordingId);

        // then
        assertThat(found).extracting(RecordingAudioChunk::getSequence)
                .containsExactly(
                        1,
                        2
                );
    }

    @Test
    @DisplayName("프레임 수와 다른 바이트 수는 DB CHECK로 거절한다")
    void update_failure_byteLengthConstraint() {
        // given
        long recordingId = saveRecording();
        chunks.save(
                chunk(
                        recordingId,
                        1
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient.sql("UPDATE recording_audio_chunks SET byte_length = 1 WHERE recording_id = :id")
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
    @DisplayName("버전 없이 VERIFIED 상태로 변경하면 DB CHECK로 거절한다")
    void update_failure_verifiedVersionRequired() {
        // given
        long recordingId = saveRecording();
        chunks.save(
                chunk(
                        recordingId,
                        1
                )
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient.sql("""
                        UPDATE recording_audio_chunks SET status = 'VERIFIED', verified_at = now()
                        WHERE recording_id = :id
                        """)
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
    @DisplayName("stream 잠금을 잡은 트랜잭션이 끝나기 전 다른 트랜잭션은 진입하지 못한다")
    void findByRecordingIdForUpdate_success_serializesThreads() throws Exception {
        // given
        TransactionTemplate transaction = independentTransaction();
        long recordingId = transaction.execute(status -> {
            long id = saveRecording();
            streams.save(RecordingAudioStream.create(id));
            return id;
        });
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                streams.findByRecordingIdForUpdate(recordingId)
                        .orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(
                    firstLocked.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                streams.findByRecordingIdForUpdate(recordingId)
                        .orElseThrow();
                secondLocked.countDown();
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
            second.get(
                    5,
                    TimeUnit.SECONDS
            );

            // then
            assertThat(enteredBeforeRelease).isFalse();
            assertThat(secondLocked.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            transaction.executeWithoutResult(
                    status -> jdbcClient.sql("DELETE FROM recording_audio_streams WHERE recording_id = :id")
                            .param(
                                    "id",
                                    recordingId
                            )
                            .update()
            );
            transaction.executeWithoutResult(
                    status -> jdbcClient.sql("DELETE FROM recording_sessions WHERE id = :id")
                            .param(
                                    "id",
                                    recordingId
                            )
                            .update()
            );
        }
    }

    @Test
    @DisplayName("예약 트랜잭션이 rollback되면 stream과 조각이 남지 않는다")
    void save_success_rollback() {
        // given
        TransactionTemplate transaction = independentTransaction();
        long recordingId = transaction.execute(status -> saveRecording());

        // when
        transaction.executeWithoutResult(status -> {
            streams.save(RecordingAudioStream.create(recordingId));
            chunks.save(
                    chunk(
                            recordingId,
                            1
                    )
            );
            status.setRollbackOnly();
        });

        // then
        assertThat(streams.findByRecordingId(recordingId)).isEmpty();
        assertThat(chunks.findAllByRecordingIdOrderBySequence(recordingId)).isEmpty();
        transaction.executeWithoutResult(
                status -> jdbcClient.sql("DELETE FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .update()
        );
    }

    private TransactionTemplate independentTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(
                    5,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException("잠금 검증 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "잠금 검증이 중단되었습니다",
                    exception
            );
        }
    }

    private AudioChunkDescriptor descriptor(int sequence) {
        return new AudioChunkDescriptor(
                sequence,
                (sequence - 1L) * 80000,
                80000,
                160000,
                HASH
        );
    }

    private RecordingAudioChunk chunk(
            long recordingId,
            int sequence
    ) {
        return RecordingAudioChunk.reserve(
                recordingId,
                descriptor(sequence),
                "chunks/" + recordingId + "/" + sequence,
                NOW.plusSeconds(60)
        );
    }

    private long saveRecording() {
        long memberId = jdbcClient.sql("INSERT INTO members (nickname) VALUES ('chunk-member') RETURNING id")
                .query(Long.class)
                .single();
        long workspaceId = jdbcClient
                .sql("INSERT INTO workspaces (name, created_at) VALUES ('조각 팀', now()) RETURNING id")
                .query(Long.class)
                .single();
        RecordingSession session = RecordingSession.start(
                workspaceId,
                memberId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0".repeat(64),
                NOW,
                RecordingAudioMode.PCM16_CHUNKS_V1
        );
        entityManager.persist(session);
        entityManager.flush();
        return session.getId();
    }
}
