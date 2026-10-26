package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.model.InputPreparationStatus;
import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class PreparedTranscriptionWindowIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String ORIGINAL_KEY = "recordings/1/audio.webm";
    private static final String ORIGINAL_VERSION = "original-v1";
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** 2분 구간 하나와 10초 tail */
    private static final long TOTAL_FRAMES = 130 * 16_000L;

    @Autowired
    private RecordingTranscriptionWindowService windowService;
    @Autowired
    private IncrementalTranscriptionCoordinator coordinator;
    @Autowired
    private RecordingTranscriptionInputRepository inputs;
    @Autowired
    private RecordingTranscriptionWindowRepository windows;
    @Autowired
    private RecordingTranscriptionRepository transcriptions;
    @Autowired
    private RecordingAudioCleanupStateService cleanupState;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private DocumentGenerationIntakeService intake;
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
        jdbc.sql("INSERT INTO members(nickname) VALUES ('준비 구간 검증')")
                .update();
        jdbc.sql("INSERT INTO workspaces(name,created_at) VALUES ('준비 구간 검증',now())")
                .update();
        jdbc.sql("""
                INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
        jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,control_token_hash,
                    status,audio_mode,started_at,last_seen_at,ended_at,end_reason,accumulated_recording_millis)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),'ENDED','FINAL_WEBM_V1',
                    CAST(:now AS TIMESTAMPTZ)-interval '60 seconds',CAST(:now AS TIMESTAMPTZ),
                    CAST(:now AS TIMESTAMPTZ),'USER_ENDED',60000)
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_uploads(recording_id,storage_key,content_type,content_length,status,
                    reserved_at,completed_at,storage_version_id)
                VALUES (1,:key,'audio/webm',100,'COMPLETED',CAST(:now AS TIMESTAMPTZ),
                    CAST(:now AS TIMESTAMPTZ),:version)
                """)
                .param(
                        "key",
                        ORIGINAL_KEY
                )
                .param(
                        "now",
                        NOW.toString()
                )
                .param(
                        "version",
                        ORIGINAL_VERSION
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_cleanup(recording_id,storage_prefix,created_at)
                VALUES (1,'recordings/1/',CAST(:now AS TIMESTAMPTZ))
                """)
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
                        ORIGINAL_KEY,
                        ORIGINAL_VERSION,
                        "audio/webm",
                        100L,
                        null
                )
        );
        RecordingTranscriptionInput prepared = RecordingTranscriptionInput.queued(
                1,
                ORIGINAL_KEY,
                ORIGINAL_VERSION,
                "audio/webm",
                100,
                NOW
        );
        prepared.claim(
                UUID.randomUUID(),
                NOW,
                Duration.ofSeconds(60)
        );
        String preparedKey = "recordings/1/prepared/" + prepared.getAttemptId() + "/" + "0".repeat(64) + ".wav";
        prepared.ready(
                preparedKey,
                new StoredAudioChunk(
                        true,
                        44 + TOTAL_FRAMES * 2,
                        "audio/wav",
                        CHECKSUM,
                        "prepared-v1"
                ),
                TOTAL_FRAMES
        );
        inputs.save(prepared);
    }

    @Test
    @DisplayName("기존 WebM 녹음도 준비 WAV로 구간을 만들고 PCM stream 없이 고정 버전을 읽는다")
    void advance_success_preparedWindowsWithoutPcmStream() {
        // given
        coordinator.advance(1);
        RecordingTranscriptionWindow window = planned().getFirst();
        TranscriptionWindowClaim claimed = claim(window);

        // when
        TranscriptionWindowAudioSource source = windowService.source(claimed)
                .orElseThrow();

        // then
        RecordingTranscriptionInput prepared = inputs.findByRecordingId(1)
                .orElseThrow();
        assertThat(planned()).hasSize(2);
        assertThat(source.finalStorageKey()).isEqualTo(prepared.getStorageKey());
        assertThat(source.finalVersionId()).isEqualTo("prepared-v1");
        assertThat(source.finalChecksumSha256()).isEqualTo(CHECKSUM);
        assertThat(source.finalByteLength()).isEqualTo(44 + TOTAL_FRAMES * 2);
        assertThat(
                mapper.readTree(source.manifestJson())
                        .path("schemaVersion")
                        .asInt()
        ).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM recording_audio_streams WHERE recording_id=1")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT incremental_pipeline_version FROM recording_sessions WHERE id=1")
                        .query(String.class)
                        .optional()
        ).isEmpty();
    }

    @Test
    @DisplayName("준비 WAV의 모든 구간을 병합하면 전사와 문서 접수를 한 번만 완료한다")
    void advance_success_mergesPreparedWindowsExactlyOnce() {
        // given
        coordinator.advance(1);
        for (RecordingTranscriptionWindow window : planned()) {
            TranscriptionWindowClaim claimed = claim(window);
            assertThat(
                    windowService.succeed(
                            claimed,
                            1,
                            mapper.writeValueAsString(evidence(window))
                    )
            ).isTrue();
        }
        coordinator.advance(1);

        // when
        coordinator.advance(1);

        // then
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM transcripts WHERE recording_session_id=1")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT text FROM transcript_segments ORDER BY position")
                        .query(String.class)
                        .list()
        ).containsExactly(
                "첫 구간",
                "끝 구간"
        );
        assertThat(
                jdbc.sql("SELECT speaker_number FROM transcript_segments WHERE speaker_number IS NOT NULL")
                        .query(Integer.class)
                        .list()
        ).isEmpty();
        verify(
                intake,
                times(1)
        ).acceptCompletedTranscript(
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "all"})
    @DisplayName("준비 원본의 권한 취소와 ALL 정리 뒤에는 진행 중 결과를 채택하지 않는다")
    void succeed_failure_latePreparedResultRejected(String condition) {
        // given
        coordinator.advance(1);
        RecordingTranscriptionWindow window = planned().getFirst();
        TranscriptionWindowClaim claimed = claim(window);
        if (condition.equals("revoked")) {
            jdbc.sql("UPDATE recording_audio_uploads SET revoked_at=CAST(:now AS TIMESTAMPTZ) WHERE recording_id=1")
                    .param(
                            "now",
                            NOW.toString()
                    )
                    .update();
        } else {
            jdbc.sql("""
                    UPDATE recording_audio_cleanup SET scope='ALL',requested_at=CAST(:now AS TIMESTAMPTZ),
                        next_attempt_at=CAST(:now AS TIMESTAMPTZ) WHERE recording_id=1
                    """)
                    .param(
                            "now",
                            NOW.toString()
                    )
                    .update();
        }

        // when
        boolean accepted = windowService.succeed(
                claimed,
                1,
                mapper.writeValueAsString(evidence(window))
        );

        // then
        assertThat(accepted).isFalse();
        assertThat(windowService.source(claimed)).isEmpty();
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM transcripts WHERE recording_session_id=1")
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    @Test
    @DisplayName("탈퇴 정리는 준비 WAV 상태와 저장된 구간 결과를 취소하고 지운다")
    void schedule_success_cancelsPreparedInputAndPurgesWindowResults() {
        // given
        coordinator.advance(1);
        RecordingTranscriptionWindow window = planned().getFirst();
        TranscriptionWindowClaim claimed = claim(window);
        assertThat(
                windowService.succeed(
                        claimed,
                        1,
                        mapper.writeValueAsString(evidence(window))
                )
        ).isTrue();
        jdbc.sql("DELETE FROM workspace_members WHERE workspace_id=1 AND member_id=1")
                .update();

        // when
        cleanupState.schedule(1);

        // then
        RecordingTranscriptionInput cancelled = inputs.findByRecordingId(1)
                .orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(InputPreparationStatus.CANCELLED);
        assertThat(cancelled.getStorageKey()).isNull();
        assertThat(cancelled.getStorageVersionId()).isNull();
        assertThat(planned()).allSatisfy(stored -> {
            assertThat(stored.getResultJson()).isNull();
            assertThat(
                    stored.getStatus()
                            .name()
            ).isEqualTo("CANCELLED");
        });
    }

    private List<RecordingTranscriptionWindow> planned() {
        return windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                RecordingTranscriptionInput.PIPELINE
        );
    }

    private TranscriptionWindowClaim claim(RecordingTranscriptionWindow window) {
        return windowService.claim(
                1,
                window.getId(),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5)
        )
                .orElseThrow();
    }

    private AiWindowEvidence evidence(RecordingTranscriptionWindow window) {
        long localStart = window.getCoreStartFrame() - window.getInputStartFrame();
        return new AiWindowEvidence(
                1,
                RecordingTranscriptionInput.PIPELINE,
                window.getManifestSha256(),
                window.getInputStartFrame(),
                window.getInputEndFrame(),
                "prepared-test-model",
                List.of(
                        new AiWindowEvidence.Token(
                                localStart,
                                localStart + 16_000,
                                window.getWindowIndex() == 0 ? "첫 구간" : "끝 구간",
                                "unknown"
                        )
                ),
                List.of(
                        new AiWindowEvidence.Speaker(
                                "unknown",
                                List.of(),
                                0
                        )
                )
        );
    }
}
