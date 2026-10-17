package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.AudioFinalizationClaim;
import com.knot.backend.recording.application.dto.result.AudioFinalizationInput;
import com.knot.backend.recording.domain.AudioFinalizationLeaseLostException;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import org.springframework.test.util.AopTestUtils;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class RecordingAudioFinalizationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T01:00:00Z");
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Autowired
    private RecordingAudioFinalizationTransaction transaction;
    @Autowired
    private RecordingAudioFinalizationRepository jobs;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private RecordingAudioFinalizationWorker worker;
    @MockitoBean
    private RecordingTranscriptionService transcriptions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private RecordingTranscriptionStateService stateService;
    @MockitoBean
    private Clock clock;
    private long recordingId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("""
                TRUNCATE TABLE recording_sessions, workspace_members, workspaces, oauth_identities,
                members RESTART IDENTITY CASCADE
                """)
                .update();
        jdbc.sql("""
                INSERT INTO members (nickname) VALUES ('최종화')
                """)
                .update();
        jdbc.sql("""
                INSERT INTO workspaces (name, created_at) VALUES ('최종화', now())
                """)
                .update();
        jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
        recordingId = jdbc.sql("""
                            INSERT INTO recording_sessions
                (workspace_id,member_id,request_id,tab_id,control_token_hash,status,audio_mode,
                                started_at,last_seen_at,ended_at,end_reason)
                            VALUES
                (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),'ENDED','PCM16_CHUNKS_V1',
                                CAST(:time AS TIMESTAMPTZ)-INTERVAL '10 seconds',CAST(:time AS
                TIMESTAMPTZ),CAST(:time AS TIMESTAMPTZ),'USER_ENDED') RETURNING id
                            """)
                .param(
                        "time",
                        NOW.toString()
                )
                .query(Long.class)
                .single();
        jdbc.sql("""
                    INSERT INTO recording_audio_streams(recording_id, next_sequence, total_frame_count,
                        seal_kind, sealed_at, accept_until, tail_unknown)
                    VALUES (:id,2,1,'NORMAL',CAST(:time AS TIMESTAMPTZ),CAST(:time AS TIMESTAMPTZ)+INTERVAL
                '120 seconds',FALSE)
                    """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "time",
                        NOW.toString()
                )
                .update();
        jdbc.sql("""
                    INSERT INTO recording_audio_chunks(recording_id, sequence, sample_offset, frame_count,
                        byte_length, checksum_sha256, storage_key, status, upload_url_expires_at)
                    VALUES (:id,1,0,1,2,:hash,'chunk-1','RESERVED',CAST(:time AS TIMESTAMPTZ)+INTERVAL '120
                seconds')
                    """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "hash",
                        HASH
                )
                .param(
                        "time",
                        NOW.toString()
                )
                .update();
        transaction.register(recordingId);
    }

    @Test
    void freezeReadsCommittedAckInsteadOfStale404() {
        // given
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        jdbc.sql("""
                UPDATE recording_audio_chunks SET status='VERIFIED',
                storage_version_id='ack-v1',verified_at=CAST(:time AS TIMESTAMPTZ) WHERE recording_id=:id
                """)
                .param(
                        "time",
                        NOW.toString()
                )
                .param(
                        "id",
                        recordingId
                )
                .update();

        // when
        AudioFinalizationInput input = transaction.reconcile(
                claim,
                Map.of(
                        1,
                        StoredAudioChunk.missing()
                ),
                clock.instant()
        )
                .orElseThrow();

        // then
        assertThat(
                input.parts()
                        .getFirst()
                        .versionId()
        ).isEqualTo("ack-v1");
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getQuality()
        ).isEqualTo("COMPLETE");
    }

    @Test
    void missingBeforeCutoffWaitsWithoutFreezing() {
        // given
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();

        // when
        java.util.Optional<AudioFinalizationInput> result = transaction.reconcile(
                claim,
                Map.of(
                        1,
                        StoredAudioChunk.missing()
                ),
                clock.instant()
        );

        // then
        assertThat(result).isEmpty();
        assertThat(
                jdbc.sql("SELECT frozen FROM recording_audio_streams WHERE recording_id=:id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(Boolean.class)
                        .single()
        ).isFalse();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getManifestJson()
        ).isNull();
    }

    @Test
    void noVerifiedAudioAfterCutoffFailsPermanentlyWithoutAdopting() {
        // given
        when(clock.instant()).thenReturn(NOW.plusSeconds(121));
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();

        // when
        java.util.Optional<AudioFinalizationInput> result = transaction.reconcile(
                claim,
                Map.of(
                        1,
                        StoredAudioChunk.missing()
                ),
                clock.instant()
        );

        // then
        assertThat(result).isEmpty();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getFailureCode()
        ).isEqualTo("AUDIO_NO_CONTENT");
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .canRetry()
        ).isFalse();
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads")
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    @Test
    void ownerLosingLeaseCannotFreezeOrSubmitFailure() {
        // given
        AudioFinalizationClaim stale = transaction.claim(recordingId)
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        AudioFinalizationClaim current = transaction.claim(recordingId)
                .orElseThrow();

        // when
        Throwable failure = catchThrowable(
                () -> transaction.reconcile(
                        stale,
                        Map.of(),
                        clock.instant()
                )
        );

        // then
        assertThat(failure).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                catchThrowable(
                        () -> transaction.fail(
                                stale,
                                "FAILED",
                                true
                        )
                )
        ).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getClaimEpoch()
        ).isEqualTo(current.claimEpoch());
    }

    @Test
    void preCutoff404CannotFreezeAfterClockCrossesCutoff() {
        // given
        when(clock.instant()).thenReturn(NOW.plusSeconds(119));
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        Instant observedAt = clock.instant();
        when(clock.instant()).thenReturn(NOW.plusSeconds(121));

        // when
        java.util.Optional<AudioFinalizationInput> result = transaction.reconcile(
                claim,
                Map.of(
                        1,
                        StoredAudioChunk.missing()
                ),
                observedAt
        );

        // then
        assertThat(result).isEmpty();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getManifestJson()
        ).isNull();
        assertThat(
                jdbc.sql("SELECT frozen FROM recording_audio_streams WHERE recording_id=:id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(Boolean.class)
                        .single()
        ).isFalse();
    }

    @Test
    void remoteSealWaitsForCutoffEvenWhenAllObjectsAreVerified() {
        // given
        jdbc.sql("UPDATE recording_audio_streams SET seal_kind='REMOTE',tail_unknown=TRUE WHERE recording_id=:id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();

        // when
        java.util.Optional<AudioFinalizationInput> result = transaction.reconcile(
                claim,
                Map.of(
                        1,
                        new StoredAudioChunk(
                                true,
                                2,
                                "application/octet-stream",
                                HASH,
                                "v1"
                        )
                ),
                NOW
        );

        // then
        assertThat(result).isEmpty();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getManifestJson()
        ).isNull();
    }

    @Test
    void adoptionAndTranscriptionQueueCommitTogether() {
        // given
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        transaction.reconcile(
                claim,
                Map.of(
                        1,
                        new StoredAudioChunk(
                                true,
                                2,
                                "application/octet-stream",
                                HASH,
                                "v1"
                        )
                ),
                clock.instant()
        );

        // when
        transaction.adopt(
                claim,
                new StoredAudioChunk(
                        true,
                        46,
                        "audio/wav",
                        HASH,
                        "final-v1"
                )
        );

        // then
        assertThat(
                jdbc.sql("SELECT content_type FROM recording_audio_uploads WHERE recording_id=:id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("audio/wav");
        assertThat(
                jdbc.sql("SELECT status FROM recording_transcriptions WHERE recording_session_id=:id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("QUEUED");
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getStatus()
                        .name()
        ).isEqualTo("READY");
    }

    @Test
    void retentionCutoffBlocksAdoptionEvenWithLiveLease() {
        // given
        when(clock.instant()).thenReturn(
                NOW.plus(java.time.Duration.ofDays(30))
                        .minusSeconds(1)
        );
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        transaction.reconcile(
                claim,
                Map.of(
                        1,
                        new StoredAudioChunk(
                                true,
                                2,
                                "application/octet-stream",
                                HASH,
                                "v1"
                        )
                ),
                clock.instant()
        );
        when(clock.instant()).thenReturn(NOW.plus(java.time.Duration.ofDays(30)));

        // when
        Throwable failure = catchThrowable(
                () -> transaction.adopt(
                        claim,
                        new StoredAudioChunk(
                                true,
                                46,
                                "audio/wav",
                                HASH,
                                "final-v1"
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads")
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    @Test
    void retentionCutoffBlocksNewClaim() {
        // given
        when(clock.instant()).thenReturn(NOW.plus(java.time.Duration.ofDays(30)));

        // when
        java.util.Optional<AudioFinalizationClaim> result = transaction.claim(recordingId);

        // then
        assertThat(result).isEmpty();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getStatus()
                        .name()
        ).isEqualTo("DISCARDED");
    }

    @Test
    void transcriptionRegistrationFailureRollsBackWavAdoptionAndReady() {
        // given
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        transaction.reconcile(
                claim,
                Map.of(
                        1,
                        new StoredAudioChunk(
                                true,
                                2,
                                "application/octet-stream",
                                HASH,
                                "v1"
                        )
                ),
                NOW
        );
        org.mockito.Mockito.doThrow(new IllegalStateException("접수 실패"))
                .when(AopTestUtils.<RecordingTranscriptionStateService>getUltimateTargetObject(stateService))
                .registerAutomatic(recordingId);

        // when
        Throwable failure = catchThrowable(
                () -> transaction.adopt(
                        claim,
                        new StoredAudioChunk(
                                true,
                                46,
                                "audio/wav",
                                HASH,
                                "final-v1"
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getStatus()
                        .name()
        ).isEqualTo("PROCESSING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"renew", "adopt", "wait", "fail"})
    void sameAttemptReplacementRejectsEveryOldOwnerWrite(String operation) {
        // given
        AudioFinalizationClaim stale = transaction.claim(recordingId)
                .orElseThrow();
        jdbc.sql("""
                UPDATE recording_audio_finalizations SET owner_token=gen_random_uuid(),claim_epoch=claim_epoch+1
                WHERE recording_id=:id
                """)
                .param(
                        "id",
                        recordingId
                )
                .update();

        // when
        Throwable failure = catchThrowable(
                () -> staleWrite(
                        operation,
                        stale
                )
        );

        // then
        assertThat(failure).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getAttempt()
        ).isEqualTo(1);
        assertThat(
                jobs.findByRecordingId(recordingId)
                        .orElseThrow()
                        .getStatus()
                        .name()
        ).isEqualTo("PROCESSING");
    }

    private void staleWrite(
            String operation,
            AudioFinalizationClaim stale
    ) {
        switch (operation) {
            case "renew" -> transaction.renew(stale);
            case "adopt" -> transaction.adopt(
                    stale,
                    new StoredAudioChunk(
                            true,
                            46,
                            "audio/wav",
                            HASH,
                            "v1"
                    )
            );
            case "wait" -> transaction.reconcile(
                    stale,
                    Map.of(
                            1,
                            StoredAudioChunk.missing()
                    ),
                    NOW
            );
            case "fail" -> transaction.fail(
                    stale,
                    "FAILED",
                    true
            );
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 동작");
        }
    }

    @Test
    void discardDuringExternalIoBlocksAdoption() {
        // given
        AudioFinalizationClaim claim = transaction.claim(recordingId)
                .orElseThrow();
        transaction.reconcile(
                claim,
                Map.of(
                        1,
                        new StoredAudioChunk(
                                true,
                                2,
                                "application/octet-stream",
                                HASH,
                                "v1"
                        )
                ),
                clock.instant()
        );
        jdbc.sql("""
                UPDATE recording_sessions SET status='DISCARDED',end_reason=NULL WHERE id=:id
                """)
                .param(
                        "id",
                        recordingId
                )
                .update();

        // when
        Throwable failure = catchThrowable(
                () -> transaction.adopt(
                        claim,
                        new StoredAudioChunk(
                                true,
                                46,
                                "audio/wav",
                                HASH,
                                "final-v1"
                        )
                )
        );

        // then
        assertThat(failure).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_uploads")
                        .query(Long.class)
                        .single()
        ).isZero();
    }
}
