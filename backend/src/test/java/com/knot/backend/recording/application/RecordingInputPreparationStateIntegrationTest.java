package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.domain.model.InputPreparationStatus;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@TestPropertySource(properties = "recording.audio-chunks.incremental-enabled=true")
@SpringBootTest
class RecordingInputPreparationStateIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(5);

    @Autowired
    private RecordingInputPreparationState state;
    @Autowired
    private RecordingTranscriptionInputRepository inputs;
    @Autowired
    private RecordingTranscriptionStateService transcriptions;
    @Autowired
    private RecordingAudioCleanupStateService cleanup;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transaction;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingTranscriptionService legacyWorker;
    @MockitoBean
    private RecordingAudioFinalizationWorker finalizer;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE recording_audio_cleanup, members, workspaces RESTART IDENTITY CASCADE")
                .update();
        jdbc.sql("INSERT INTO members(nickname) VALUES ('입력 준비')")
                .update();
        jdbc.sql("INSERT INTO workspaces(name,created_at) VALUES ('입력 준비',now())")
                .update();
        jdbc.sql("""
                INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
        recording();
    }

    @Test
    void registerAutomatic_preservesOriginalVersionAndCreatesOneDurablePreparation() {
        // given
        register(1);

        // when
        register(1);

        // then
        RecordingTranscriptionInput input = inputs.findByRecordingId(1)
                .orElseThrow();
        assertThat(input.getStatus()).isEqualTo(InputPreparationStatus.QUEUED);
        assertThat(input.getSourceVersionId()).isEqualTo("original-v1");
        assertThat(
                jdbc.sql("SELECT storage_key FROM recording_audio_uploads WHERE recording_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("recordings/1/original.webm");
        assertThat(
                jdbc.sql("""
                        SELECT COUNT(*) FROM recording_sessions
                        WHERE id=1 AND incremental_pipeline_version IS NOT NULL
                        """)
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM recording_transcription_inputs")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT scope FROM recording_audio_cleanup WHERE recording_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("DORMANT");
    }

    @Test
    void preparationDoesNotExpireAfterOneYear() {
        register(1);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(365)));
        assertThat(
                state.claim(
                        1,
                        LEASE
                )
        ).isPresent();
    }

    @Test
    void claim_recoversSameAttemptAndRejectsStaleReadyResult() {
        // given
        register(1);
        RecordingInputPreparationState.Claim first = state.claim(
                1,
                LEASE
        )
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(6));
        RecordingInputPreparationState.Claim recovered = state.claim(
                1,
                LEASE
        )
                .orElseThrow();
        assertThat(
                state.ready(
                        first,
                        key(first),
                        stored(),
                        10
                )
        ).isFalse();

        // when
        boolean adopted = state.ready(
                recovered,
                key(recovered),
                stored(),
                10
        );

        // then
        assertThat(adopted).isTrue();
        assertThat(recovered.attemptId()).isEqualTo(first.attemptId());
        assertThat(recovered.epoch()).isEqualTo(first.epoch() + 1);
        assertThat(
                inputs.findByRecordingId(1)
                        .orElseThrow()
                        .getStorageVersionId()
        ).isEqualTo("prepared-v1");
    }

    @Test
    void claim_concurrentPreparationsUseIndependentSinglePreparationSlot() throws Exception {
        // given
        recording();
        register(1);
        register(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                return state.claim(
                        1,
                        LEASE
                )
                        .isPresent();
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                return state.claim(
                        2,
                        LEASE
                )
                        .isPresent();
            });

            // when
            int claimed = (first.get(
                    10,
                    TimeUnit.SECONDS
            ) ? 1 : 0)
                    + (second.get(
                            10,
                            TimeUnit.SECONDS
                    ) ? 1 : 0);

            // then
            assertThat(claimed).isEqualTo(1);
            assertThat(
                    jdbc.sql("SELECT COUNT(*) FROM recording_transcription_inputs WHERE status='PROCESSING'")
                            .query(Long.class)
                            .single()
            ).isEqualTo(1);
        }
    }

    @Test
    void afterDeparture_cancelsLegacyPreparationAndFencesLateUpload() {
        // given
        register(1);
        RecordingInputPreparationState.Claim claim = state.claim(
                1,
                LEASE
        )
                .orElseThrow();
        transaction.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM workspace_members WHERE workspace_id=1 AND member_id=1")
                    .update();
            cleanup.afterDeparture(
                    1,
                    1
            );
        });

        // when
        boolean adopted = state.ready(
                claim,
                key(claim),
                stored(),
                10
        );

        // then
        assertThat(adopted).isFalse();
        assertThat(
                inputs.findByRecordingId(1)
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(InputPreparationStatus.CANCELLED);
        assertThat(
                jdbc.sql("SELECT scope FROM recording_audio_cleanup WHERE recording_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("ALL");
    }

    @Test
    void expire_failsParentAndExplicitRetryStartsNewPreparationAttempt() {
        // given
        register(1);
        RecordingInputPreparationState.Claim original = state.claim(
                1,
                LEASE
        )
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(15)));
        assertThat(
                state.claim(
                        1,
                        LEASE
                )
        ).isEmpty();
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");

        // when
        transcriptions.retry(
                1,
                1,
                1
        );

        // then
        RecordingTranscriptionInput input = inputs.findByRecordingId(1)
                .orElseThrow();
        assertThat(input.getStatus()).isEqualTo(InputPreparationStatus.QUEUED);
        assertThat(input.getAttemptId()).isNotEqualTo(original.attemptId());
        assertThat(input.getExecutionDeadline()).isNull();
        assertThat(input.getSourceVersionId()).isEqualTo("original-v1");
    }

    private void register(long recordingId) {
        transaction.executeWithoutResult(status -> transcriptions.registerAutomatic(recordingId));
    }

    private void recording() {
        long id = jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,
                    control_token_hash,status,started_at,last_seen_at,ended_at,end_reason)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),
                    'ENDED',:now,:now,:now,'USER_ENDED') RETURNING id
                """)
                .param(
                        "now",
                        java.sql.Timestamp.from(NOW)
                )
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO recording_audio_uploads(recording_id,storage_key,content_type,content_length,status,
                    reserved_at,completed_at,storage_version_id)
                VALUES (:id,:key,'audio/webm',100,'COMPLETED',:now,:now,'original-v1')
                """)
                .param(
                        "id",
                        id
                )
                .param(
                        "key",
                        "recordings/" + id + "/original.webm"
                )
                .param(
                        "now",
                        java.sql.Timestamp.from(NOW)
                )
                .update();
    }

    private String key(RecordingInputPreparationState.Claim claim) {
        return "recordings/" + claim.recordingId() + "/prepared/" + claim.attemptId() + "/" + HexFormat.of()
                .formatHex(new byte[32]) + ".wav";
    }

    private StoredAudioChunk stored() {
        return new StoredAudioChunk(
                true,
                64,
                "audio/wav",
                Base64.getEncoder()
                        .encodeToString(new byte[32]),
                "prepared-v1"
        );
    }
}
