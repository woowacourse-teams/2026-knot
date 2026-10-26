package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.TranscriptionWindowStatus;
import com.knot.backend.recording.domain.model.TranscriptionDecodePolicy;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyLong;

import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class IncrementalTranscriptionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String PIPELINE = "pcm-window-v1-30-5";

    @Autowired
    private RecordingTranscriptionWindowService windowService;
    @Autowired
    private RecordingTranscriptionStateService transcriptionState;
    @Autowired
    private RecordingTranscriptionRepository transcriptions;
    @Autowired
    private RecordingTranscriptionWindowRepository windows;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private IncrementalTranscriptionCoordinator coordinator;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private com.knot.backend.global.config.RecordingAudioChunksProperties audioProperties;
    @MockitoBean
    private DocumentGenerationIntakeService intake;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    @MockitoBean
    private RecordingAudioFinalizationWorker finalizer;
    @MockitoBean
    private RecordingTranscriptionService transcriptionWorker;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE recording_audio_cleanup, members, workspaces RESTART IDENTITY CASCADE")
                .update();
        jdbc.sql("INSERT INTO members (nickname) VALUES ('구간 검증')")
                .update();
        jdbc.sql("INSERT INTO workspaces (name,created_at) VALUES ('구간 검증',now())")
                .update();
        jdbc.sql("""
                INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
        jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,control_token_hash,
                    status,audio_mode,started_at,last_seen_at,current_interval_started_at,incremental_pipeline_version)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),'RECORDING','PCM16_CHUNKS_V1',
                    CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),:pipeline)
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .param(
                        "pipeline",
                        PIPELINE
                )
                .update();
        jdbc.sql("INSERT INTO recording_audio_streams(recording_id) VALUES (1)")
                .update();
    }

    @Test
    void claim_concurrentLegacyAndWindow_shareOneAdmissionSlot() throws Exception {
        // given
        RecordingTranscriptionWindow window = saveWindow(0);
        saveLegacy();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> legacy = executor.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                return transcriptionState.claimNext(
                        UUID.randomUUID(),
                        Duration.ofSeconds(30)
                )
                        .isPresent();
            });
            Future<Boolean> incremental = executor.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                return claim(window.getId()).isPresent();
            });

            // when
            int claimed = (legacy.get(
                    10,
                    TimeUnit.SECONDS
            ) ? 1 : 0)
                    + (incremental.get(
                            10,
                            TimeUnit.SECONDS
                    ) ? 1 : 0);

            // then
            assertThat(claimed).isEqualTo(1);
            assertThat(countProcessing()).isEqualTo(1);
        }
    }

    @Test
    void claim_legacyWaitsUntilWindowResultIsPersisted() {
        // given
        RecordingTranscriptionWindow window = saveWindow(0);
        saveLegacy();
        TranscriptionWindowClaim claimed = claim(window.getId()).orElseThrow();
        assertThat(
                transcriptionState.claimNext(
                        UUID.randomUUID(),
                        Duration.ofSeconds(30)
                )
        ).isEmpty();
        assertThat(
                windowService.succeed(
                        claimed,
                        1,
                        "{\"schemaVersion\":1}"
                )
        ).isTrue();

        // when
        boolean admitted = transcriptionState.claimNext(
                UUID.randomUUID(),
                Duration.ofSeconds(30)
        )
                .isPresent();

        // then
        assertThat(admitted).isTrue();
        assertThat(countProcessing()).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT result_json FROM recording_transcription_windows WHERE id=:id")
                        .param(
                                "id",
                                window.getId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("{\"schemaVersion\":1}");
    }

    @Test
    void claim_windowWaitsWhileLegacyInferenceIsProcessing() {
        // given
        RecordingTranscriptionWindow window = saveWindow(0);
        saveLegacy();
        assertThat(
                transcriptionState.claimNext(
                        UUID.randomUUID(),
                        Duration.ofSeconds(30)
                )
        ).isPresent();

        // when
        Optional<TranscriptionWindowClaim> result = claim(window.getId());

        // then
        assertThat(result).isEmpty();
        assertThat(countProcessing()).isEqualTo(1);
    }

    @Test
    void claim_secondWindowWaitsForDurableFirstResult() {
        // given
        RecordingTranscriptionWindow first = saveWindow(0);
        RecordingTranscriptionWindow second = saveWindow(1);
        TranscriptionWindowClaim claimed = claim(first.getId()).orElseThrow();
        assertThat(claim(second.getId())).isEmpty();
        assertThat(
                windowService.succeed(
                        claimed,
                        1,
                        "{\"schemaVersion\":1}"
                )
        ).isTrue();

        // when
        Optional<TranscriptionWindowClaim> next = claim(second.getId());

        // then
        assertThat(next).isPresent();
        assertThat(
                jdbc.sql("SELECT result_json FROM recording_transcription_windows WHERE id=:id")
                        .param(
                                "id",
                                first.getId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("{\"schemaVersion\":1}");
        assertThat(countProcessing()).isEqualTo(1);
    }

    @Test
    void claim_expiredLeasePreservesAttemptAndRejectsPreviousOwner() {
        // given
        RecordingTranscriptionWindow window = saveWindow(0);
        TranscriptionWindowClaim original = claim(window.getId()).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(31));

        // when
        TranscriptionWindowClaim recovered = claim(window.getId()).orElseThrow();

        // then
        assertThat(recovered.attemptId()).isEqualTo(original.attemptId());
        assertThat(recovered.claimEpoch()).isEqualTo(original.claimEpoch() + 1);
        assertThat(
                windowService.succeed(
                        original,
                        1,
                        "{}"
                )
        ).isFalse();
        assertThat(countProcessing()).isEqualTo(1);
    }

    @Test
    void advance_frozenFinalTailMergesOnceWhileLegacyInferenceIsProcessing() throws Exception {
        // given
        prepareFinalSource();
        coordinator.advance(1);
        RecordingTranscriptionWindow tail = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        TranscriptionWindowClaim claim = claim(tail.getId()).orElseThrow();
        AiWindowEvidence evidence = new AiWindowEvidence(
                1,
                PIPELINE,
                tail.getManifestSha256(),
                0,
                80000,
                "model-v1",
                List.of(
                        new AiWindowEvidence.Token(
                                0,
                                16000,
                                "안녕하세요",
                                "speaker-1"
                        )
                ),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "speaker-1",
                                List.of(),
                                16000
                        )
                )
        );
        assertThat(
                windowService.succeed(
                        claim,
                        1,
                        mapper.writeValueAsString(evidence)
                )
        ).isTrue();
        saveLegacy();
        assertThat(
                transcriptionState.claimNext(
                        UUID.randomUUID(),
                        Duration.ofSeconds(30)
                )
        ).isPresent();
        coordinator.advance(1);

        // when
        coordinator.advance(1);

        // then
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM transcripts WHERE recording_session_id=1")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=2")
                        .query(String.class)
                        .single()
        ).isEqualTo("PROCESSING");
        verify(
                intake,
                times(1)
        ).acceptCompletedTranscript(
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void claim_persistedPipelineRemainsSelectedWithDefaultFeatureFlagDisabled() {
        // given
        assertThat(audioProperties.incrementalEnabled()).isFalse();
        RecordingTranscriptionWindow window = saveWindow(0);

        // when
        Optional<TranscriptionWindowClaim> claim = claim(window.getId());

        // then
        assertThat(claim).isPresent();
        assertThat(
                jdbc.sql("SELECT incremental_pipeline_version FROM recording_sessions WHERE id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo(PIPELINE);
    }

    @Test
    void source_afterRawDeletionUsesFrozenFinalVersion() throws Exception {
        // given
        prepareFinalSource();
        coordinator.advance(1);
        RecordingTranscriptionWindow tail = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        TranscriptionWindowClaim claimed = claim(tail.getId()).orElseThrow();
        jdbc.sql("DELETE FROM recording_audio_chunks WHERE recording_id=1")
                .update();

        // when
        com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource source = windowService
                .source(claimed)
                .orElseThrow();

        // then
        assertThat(source.finalVersionId()).isEqualTo("final-v1");
        assertThat(source.finalStorageKey()).startsWith("recordings/1/final/1/");
        assertThat(source.finalByteLength()).isEqualTo(160044);
        assertThat(source.manifestSha256()).isEqualTo(tail.getManifestSha256());
    }

    @Test
    void source_rejectsFinalManifestWithDifferentInputVersion() throws Exception {
        // given
        prepareFinalSource();
        coordinator.advance(1);
        RecordingTranscriptionWindow tail = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        TranscriptionWindowClaim claimed = claim(tail.getId()).orElseThrow();
        jdbc.sql("""
                UPDATE recording_audio_finalizations
                SET manifest_json=replace(manifest_json,'chunk-v1','changed-v2') WHERE recording_id=1
                """)
                .update();

        // when
        Throwable failure = catchThrowable(() -> windowService.source(claimed));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    void explicitRetry_failedWindowGetsNewAttemptAndCompletesDocumentOnce() throws Exception {
        // given
        prepareFinalSource();
        coordinator.advance(1);
        RecordingTranscriptionWindow original = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        TranscriptionWindowClaim oldClaim = claim(original.getId()).orElseThrow();
        assertThat(
                windowService.fail(
                        oldClaim,
                        "STT_FAILED"
                )
        ).isTrue();
        coordinator.advance(1);
        assertThat(
                transcriptionState.findSnapshot(1)
                        .failureCode()
        ).isEqualTo("INCREMENTAL_WINDOW_FAILED");
        // when
        transcriptionState.retry(
                1,
                1,
                1
        );
        // then
        completeRetriedWindow(
                original,
                oldClaim
        );
    }

    @Test
    void explicitRetry_mergeFailureRegeneratesPreviouslySuccessfulEvidence() throws Exception {
        // given
        prepareFinalSource();
        coordinator.advance(1);
        RecordingTranscriptionWindow original = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        TranscriptionWindowClaim oldClaim = claim(original.getId()).orElseThrow();
        assertThat(
                windowService.succeed(
                        oldClaim,
                        1,
                        mapper.writeValueAsString(tailEvidence(original))
                )
        ).isTrue();
        transcriptionState.failIncremental(
                1,
                "INCREMENTAL_MERGE_FAILED"
        );
        // when
        transcriptionState.retry(
                1,
                1,
                1
        );
        // then
        completeRetriedWindow(
                original,
                oldClaim
        );
    }

    private void completeRetriedWindow(
            RecordingTranscriptionWindow original,
            TranscriptionWindowClaim oldClaim
    ) throws Exception {
        RecordingTranscriptionWindow reset = windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        )
                .getFirst();
        assertThat(reset.getAttemptId()).isNotEqualTo(original.getAttemptId());
        assertThat(reset.getExecutionDeadline()).isNull();
        assertThat(reset.getStatus()).isEqualTo(TranscriptionWindowStatus.QUEUED);
        assertThat(reset.getResultJson()).isNull();
        assertThat(reset.getResultSha256()).isNull();
        assertThat(
                windowService.succeed(
                        oldClaim,
                        1,
                        "{}"
                )
        ).isFalse();
        TranscriptionWindowClaim next = claim(reset.getId()).orElseThrow();
        assertThat(
                windowService.succeed(
                        next,
                        1,
                        mapper.writeValueAsString(tailEvidence(reset))
                )
        ).isTrue();
        coordinator.advance(1);
        coordinator.advance(1);
        assertThat(
                transcriptionState.findSnapshot(1)
                        .status()
        ).isEqualTo(TranscriptionStatus.SUCCEEDED);
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM transcripts WHERE recording_session_id=1")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        verify(
                intake,
                times(1)
        ).acceptCompletedTranscript(
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    private AiWindowEvidence tailEvidence(RecordingTranscriptionWindow window) {
        return new AiWindowEvidence(
                1,
                PIPELINE,
                window.getManifestSha256(),
                0,
                80000,
                "model-v1",
                List.of(
                        new AiWindowEvidence.Token(
                                0,
                                16000,
                                "다시 완료",
                                "speaker-1"
                        )
                ),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "speaker-1",
                                List.of(),
                                16000
                        )
                )
        );
    }

    private void prepareFinalSource() throws Exception {
        String checksum = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String manifest = mapper.writeValueAsString(
                List.of(
                        new AudioManifestPart(
                                1,
                                0,
                                80000,
                                "recordings/1/chunks/1",
                                checksum,
                                "chunk-v1"
                        )
                )
        );
        String manifestHash = java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                );
        String key = "recordings/1/final/1/" + manifestHash + ".wav";
        jdbc.sql("""
                UPDATE recording_sessions SET status='ENDED',ended_at=CAST(:now AS TIMESTAMPTZ),
                    end_reason='USER_ENDED',current_interval_started_at=NULL WHERE id=1
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                UPDATE recording_audio_streams SET total_frame_count=80000,next_sequence=2,frozen=TRUE,
                    seal_kind='NORMAL',sealed_at=CAST(:now AS TIMESTAMPTZ),
                    accept_until=CAST(:now AS TIMESTAMPTZ)+interval '120 seconds' WHERE recording_id=1
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_finalizations(recording_id,status,created_at,next_run_at,
                    manifest_json,manifest_hash,quality,final_key,final_version_id,
                    final_checksum,final_byte_length,ready_at)
                VALUES (1,'READY',CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),:manifest,:manifestHash,
                    'COMPLETE',:key,'final-v1',:checksum,160044,CAST(:now AS TIMESTAMPTZ))
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .param(
                        "manifest",
                        manifest
                )
                .param(
                        "manifestHash",
                        manifestHash
                )
                .param(
                        "key",
                        key
                )
                .param(
                        "checksum",
                        checksum
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_uploads(recording_id,storage_key,content_type,content_length,status,
                    reserved_at,completed_at,storage_version_id)
                VALUES (1,:key,'audio/wav',160044,'COMPLETED',
                    CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),'final-v1')
                """)
                .param(
                        "key",
                        key
                )
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        transcriptions.saveAndFlush(
                RecordingTranscription.queued(
                        1,
                        UUID.randomUUID(),
                        NOW,
                        key,
                        "final-v1",
                        "audio/wav",
                        160044L,
                        null
                )
        );
    }

    @Test
    @DisplayName("녹음 pipeline은 기존 30초 버전과 새 2분 버전만 허용한다")
    void migration_success_pipelineVersionsCoexist() {
        // given
        String update = "UPDATE recording_sessions SET incremental_pipeline_version=:pipeline WHERE id=1";

        // when
        int twoMinutes = jdbc.sql(update)
                .param(
                        "pipeline",
                        TranscriptionWindowShape.LIVE_TWO_MINUTES.pipelineVersion()
                )
                .update();
        int thirtySeconds = jdbc.sql(update)
                .param(
                        "pipeline",
                        PIPELINE
                )
                .update();
        Throwable unknown = catchThrowable(
                () -> jdbc.sql(update)
                        .param(
                                "pipeline",
                                "pcm-window-v3"
                        )
                        .update()
        );

        // then
        assertThat(twoMinutes + thirtySeconds).isEqualTo(2);
        assertThat(unknown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("2분 구간의 디코딩 정책을 저장하고 claim으로 AI 요청까지 전달한다")
    void claim_success_twoMinuteDecodePolicy() {
        // given
        RecordingTranscriptionWindow saved = windows.save(
                RecordingTranscriptionWindow.queued(
                        1,
                        PIPELINE,
                        0,
                        0,
                        480000,
                        0,
                        480000,
                        "{\"schemaVersion\":1}",
                        TranscriptionDecodePolicy.WITH_VOCABULARY,
                        UUID.randomUUID(),
                        NOW
                )
        );

        // when
        Optional<TranscriptionWindowClaim> claimed = claim(saved.getId());

        // then
        assertThat(claimed).map(TranscriptionWindowClaim::decodePolicy)
                .contains(TranscriptionDecodePolicy.WITH_VOCABULARY);
        assertThat(
                jdbc.sql("SELECT decode_policy FROM recording_transcription_windows WHERE id=:id")
                        .param(
                                "id",
                                saved.getId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("WITH_VOCABULARY");
    }

    private RecordingTranscriptionWindow saveWindow(int index) {
        return windows.save(
                RecordingTranscriptionWindow.queued(
                        1,
                        PIPELINE,
                        index,
                        index * 480000L,
                        (index + 1L) * 480000,
                        index * 480000L,
                        (index + 1L) * 480000,
                        "{\"schemaVersion\":1}",
                        UUID.randomUUID(),
                        NOW
                )
        );
    }

    private Optional<TranscriptionWindowClaim> claim(long windowId) {
        return windowService.claim(
                1,
                windowId,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5)
        );
    }

    private void saveLegacy() {
        jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,control_token_hash,
                    status,started_at,last_seen_at,ended_at,end_reason)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('b',64),'ENDED',
                    CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),'USER_ENDED')
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        transcriptions.saveAndFlush(
                RecordingTranscription.queued(
                        2,
                        UUID.randomUUID(),
                        NOW,
                        "recordings/2/audio.webm",
                        "v1",
                        "audio/webm",
                        100L,
                        null
                )
        );
    }

    private long countProcessing() {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM recording_transcriptions WHERE status='PROCESSING') +
                    (SELECT COUNT(*) FROM recording_transcription_windows WHERE status='PROCESSING')
                """)
                .query(Long.class)
                .single();
    }
}
