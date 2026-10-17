package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;

import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest(properties = "recording.audio-chunks.enabled=true")
class RecordingAudioSealingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:10Z");
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final UUID TAB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final RecordingControlCommand CONTROL = createControl();

    @Autowired
    private RecordingAudioChunkService service;
    @Autowired
    private RecordingControlService controls;
    @Autowired
    private RecordingConnectionExpiryTransaction expiry;
    @Autowired
    private RecordingControlTokenHasher hasher;
    @Autowired
    private JdbcClient jdbcClient;
    @MockitoBean
    private RecordingAudioChunkStorage storage;
    @MockitoBean
    private Clock clock;
    private long memberId;
    private long workspaceId;
    private long recordingId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_members, workspaces,
                    oauth_identities, members RESTART IDENTITY CASCADE
                """)
                .update();
        memberId = jdbcClient.sql("INSERT INTO members (nickname) VALUES ('chunk-member') RETURNING id")
                .query(Long.class)
                .single();
        workspaceId = jdbcClient.sql("INSERT INTO workspaces (name, created_at) VALUES ('조각 팀', now()) RETURNING id")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, last_viewed)
                VALUES (:workspaceId, :memberId, 'OWNER', now(), FALSE)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .update();
        recordingId = jdbcClient.sql("""
                INSERT INTO recording_sessions (
                    workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                    audio_mode, started_at, current_interval_started_at, last_seen_at
                ) VALUES (:workspaceId, :memberId, :requestId, :tabId, :hash, 'RECORDING',
                    'PCM16_CHUNKS_V1', CAST(:startedAt AS TIMESTAMPTZ), CAST(:startedAt AS TIMESTAMPTZ),
                    CAST(:startedAt AS TIMESTAMPTZ)) RETURNING id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "requestId",
                        UUID.randomUUID()
                )
                .param(
                        "tabId",
                        TAB
                )
                .param(
                        "hash",
                        hasher.hash(TOKEN)
                )
                .param(
                        "startedAt",
                        NOW.minusSeconds(10)
                                .toString()
                )
                .query(Long.class)
                .single();
        jdbcClient.sql("INSERT INTO recording_audio_streams (recording_id) VALUES (:id)")
                .param(
                        "id",
                        recordingId
                )
                .update();
        when(
                storage.presignUpload(
                        anyString(),
                        anyLong(),
                        anyString(),
                        any()
                )
        ).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Instant expiresAt = invocation.getArgument(3);
            return new PresignedAudioChunk(
                    "https://storage.invalid/signed",
                    expiresAt.minusSeconds(1),
                    Map.of(
                            "Content-Type",
                            "application/octet-stream"
                    )
            );
        });
        when(storage.findStoredObject(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return stored();
        });
    }

    @Test
    void normalSealRegistersTailAndReplaysWithoutExtending() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        78400
                )
        );
        com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand seal = seal(
                2,
                80000,
                List.of(
                        descriptor(
                                2,
                                78400,
                                1600
                        )
                )
        );
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        // when
        RecordingEndResult repeated = controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal
        );
        // then
        assertThat(count("recording_audio_chunks")).isEqualTo(2);
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("NORMAL");
        assertThat(
                value(
                        "total_frame_count",
                        Long.class
                )
        ).isEqualTo(80000);
        assertThat(
                value(
                        "tail_unknown",
                        Boolean.class
                )
        ).isFalse();
        assertThat(
                value(
                        "accept_until",
                        Instant.class
                )
        ).isEqualTo(NOW.plusSeconds(120));
        assertThat(repeated.endedAt()).isEqualTo(NOW);
        assertThat(
                repeated.audioSave()
                        .expectedLastSequence()
        ).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {78400, 80000, 81600})
    void exactTailBoundariesAreSealed(long frames) {
        // given
        List<AudioChunkDescriptor> descriptors = frames <= 80000
                ? List.of(
                        descriptor(
                                1,
                                0,
                                (int) frames
                        )
                )
                : List.of(
                        descriptor(
                                1,
                                0,
                                80000
                        ),
                        descriptor(
                                2,
                                80000,
                                (int) (frames - 80000)
                        )
                );
        com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand seal = seal(
                descriptors.size(),
                frames,
                descriptors
        );
        // when
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal
        );
        // then
        assertThat(
                value(
                        "total_frame_count",
                        Long.class
                )
        ).isEqualTo(frames);
        assertThat(count("recording_audio_chunks")).isEqualTo(descriptors.size());
    }

    @Test
    void invalidNormalSealRollsBackTailAndSessionTogether() {
        // given
        com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand seal = seal(
                2,
                1600,
                List.of(
                        descriptor(
                                1,
                                0,
                                1600
                        )
                )
        );
        // when
        Throwable failure = catchThrowable(
                () -> controls.end(
                        workspaceId,
                        memberId,
                        recordingId,
                        CONTROL,
                        seal
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(count("recording_audio_chunks")).isZero();
        assertThat(
                value(
                        "sealed_at",
                        Instant.class
                )
        ).isNull();
        assertThat(sessionStatus()).isEqualTo("RECORDING");
    }

    @Test
    void remoteEndSealsKnownRangeAndRefusesNewTail() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        80000
                )
        );
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                null
        );
        // when
        Throwable failure = catchThrowable(
                () -> service.reserve(
                        workspaceId,
                        memberId,
                        recordingId,
                        command(
                                2,
                                80000,
                                1
                        )
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("REMOTE");
        assertThat(
                value(
                        "tail_unknown",
                        Boolean.class
                )
        ).isTrue();
        assertThat(count("recording_audio_chunks")).isEqualTo(1);
        assertThat(
                service.findState(
                        workspaceId,
                        memberId,
                        recordingId
                )
                        .expectedLastSequence()
        ).isEqualTo(1);
    }

    @Test
    void endedReservationPresignIsCappedAndCutoffRejectsNewConfirmation() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        80000
                )
        );
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                null
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(118));
        RecordingAudioChunkReservationResult issued = service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        80000
                )
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        // when
        Throwable failure = catchThrowable(
                () -> service.complete(
                        workspaceId,
                        memberId,
                        recordingId,
                        1,
                        CONTROL
                )
        );
        // then
        assertThat(issued.expiresAt()).isBeforeOrEqualTo(NOW.plusSeconds(120));
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                value(
                        "accept_until",
                        Instant.class
                )
        ).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void timeoutTransitionCommitsEvenRejectedNewDescriptor() {
        // given
        when(clock.instant()).thenReturn(NOW.plusSeconds(25));
        // when
        Throwable failure = catchThrowable(
                () -> service.reserve(
                        workspaceId,
                        memberId,
                        recordingId,
                        command(
                                1,
                                0,
                                80000
                        )
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(sessionStatus()).isEqualTo("ENDED");
        assertThat(
                value(
                        "sealed_at",
                        Instant.class
                )
        ).isEqualTo(NOW.plusSeconds(20));
        assertThat(
                value(
                        "accept_until",
                        Instant.class
                )
        ).isEqualTo(NOW.plusSeconds(140));
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("TIMEOUT");
    }

    @Test
    void delayedWorkerUsesLogicalTimeAndDurableEnumeration() {
        // given
        when(clock.instant()).thenReturn(NOW.plusSeconds(200));
        // when
        boolean expired = expiry.expire(recordingId);
        // then
        assertThat(expired).isTrue();
        assertThat(
                value(
                        "accept_until",
                        Instant.class
                )
        ).isEqualTo(NOW.plusSeconds(140));
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("TIMEOUT");
    }

    @Test
    void emptyNormalSealPersistsZeroFrames() {
        // given
        com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand seal = seal(
                0,
                0,
                List.of()
        );
        // when
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal
        );
        // then
        assertThat(
                value(
                        "total_frame_count",
                        Long.class
                )
        ).isZero();
        assertThat(
                value(
                        "tail_unknown",
                        Boolean.class
                )
        ).isFalse();
        assertThat(count("recording_audio_chunks")).isZero();
    }

    @Test
    void changedSealAfterNormalEndConflicts() {
        // given
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal(
                        0,
                        0,
                        List.of()
                )
        );
        // when
        Throwable failure = catchThrowable(
                () -> controls.end(
                        workspaceId,
                        memberId,
                        recordingId,
                        CONTROL,
                        seal(
                                1,
                                1,
                                List.of(
                                        descriptor(
                                                1,
                                                0,
                                                1
                                        )
                                )
                        )
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                value(
                        "total_frame_count",
                        Long.class
                )
        ).isZero();
    }

    @Test
    void verifiedAcknowledgementSurvivesCutoffAndFreeze() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        80000
                )
        );
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                null
        );
        service.complete(
                workspaceId,
                memberId,
                recordingId,
                1,
                CONTROL
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        jdbcClient.sql("UPDATE recording_audio_streams SET frozen = TRUE WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        // when
        RecordingAudioChunkCompletionResult result = service.complete(
                workspaceId,
                memberId,
                recordingId,
                1,
                CONTROL
        );
        // then
        assertThat(result.verifiedThroughFrame()).isEqualTo(80000);
    }

    @Test
    void completeRechecksCutoffAfterStorageCall() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        80000
                )
        );
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                null
        );
        when(storage.findStoredObject(anyString())).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(120));
            return stored();
        });
        // when
        Throwable failure = catchThrowable(
                () -> service.complete(
                        workspaceId,
                        memberId,
                        recordingId,
                        1,
                        CONTROL
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(
                jdbcClient.sql("SELECT status FROM recording_audio_chunks WHERE recording_id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("RESERVED");
    }

    @Test
    void pauseRequestCommitsExpiredSealDespiteConflict() {
        // given
        when(clock.instant()).thenReturn(NOW.plusSeconds(25));
        // when
        Throwable failure = catchThrowable(
                () -> controls.pause(
                        workspaceId,
                        memberId,
                        recordingId,
                        CONTROL
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(sessionStatus()).isEqualTo("ENDED");
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("TIMEOUT");
    }

    @Test
    void maxDurationSealsAtLogicalLimit() {
        // given
        jdbcClient.sql("UPDATE recording_sessions SET accumulated_recording_millis = 7191000 WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
        // when
        boolean expired = expiry.expire(recordingId);
        // then
        assertThat(expired).isTrue();
        assertThat(
                value(
                        "seal_kind",
                        String.class
                )
        ).isEqualTo("LIMIT");
        assertThat(
                value(
                        "sealed_at",
                        Instant.class
                )
        ).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void pausedEndDoesNotInventSilence() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        0,
                        1
                )
        );
        controls.pause(
                workspaceId,
                memberId,
                recordingId,
                CONTROL
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        // when
        controls.end(
                workspaceId,
                memberId,
                recordingId,
                CONTROL,
                seal(
                        1,
                        1,
                        List.of()
                )
        );
        // then
        assertThat(
                value(
                        "total_frame_count",
                        Long.class
                )
        ).isEqualTo(1);
        assertThat(
                value(
                        "tail_unknown",
                        Boolean.class
                )
        ).isFalse();
    }

    @Test
    void reserveAndRemoteEndConvergeUnderConcurrentDatabaseLocks() throws Exception {
        // given
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Throwable> reserve = executor.submit(() -> {
                barrier.await();
                return catchThrowable(
                        () -> service.reserve(
                                workspaceId,
                                memberId,
                                recordingId,
                                command(
                                        1,
                                        0,
                                        80000
                                )
                        )
                );
            });
            java.util.concurrent.Future<RecordingEndResult> end = executor.submit(() -> {
                barrier.await();
                return controls.end(
                        workspaceId,
                        memberId,
                        recordingId,
                        null
                );
            });
            // when
            RecordingEndResult ended = end.get(
                    10,
                    java.util.concurrent.TimeUnit.SECONDS
            );
            // then
            Throwable reservationFailure = reserve.get(
                    10,
                    java.util.concurrent.TimeUnit.SECONDS
            );
            assertThat(ended.status()).isEqualTo(com.knot.backend.recording.domain.model.RecordingStatus.ENDED);
            assertThat(
                    value(
                            "next_sequence",
                            Integer.class
                    )
            ).isEqualTo(count("recording_audio_chunks") + 1);
            assertThat(
                    value(
                            "total_frame_count",
                            Long.class
                    )
            ).isEqualTo(count("recording_audio_chunks") * 80000L);
            if (reservationFailure != null) {
                assertThat(reservationFailure).isInstanceOf(RecordingException.class);
            }
        }
    }

    private RecordingAudioChunkCommand command(
            int sequence,
            long offset,
            int frames
    ) {
        return new RecordingAudioChunkCommand(
                CONTROL,
                descriptor(
                        sequence,
                        offset,
                        frames
                )
        );
    }

    private AudioChunkDescriptor descriptor(
            int sequence,
            long offset,
            int frames
    ) {
        return new AudioChunkDescriptor(
                sequence,
                offset,
                frames,
                frames * 2L,
                HASH
        );
    }

    private com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand seal(
            int sequence,
            long frames,
            List<AudioChunkDescriptor> descriptors
    ) {
        return new com.knot.backend.recording.application.dto.command.RecordingAudioSealCommand(
                sequence,
                frames,
                descriptors
        );
    }

    private <T> T value(
            String column,
            Class<T> type
    ) {
        return jdbcClient.sql("SELECT " + column + " FROM recording_audio_streams WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(type)
                .optional()
                .orElse(null);
    }

    private int count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table + " WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(Integer.class)
                .single();
    }

    private String sessionStatus() {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private StoredAudioChunk stored() {
        return new StoredAudioChunk(
                true,
                160000,
                "application/octet-stream",
                HASH,
                "version-1"
        );
    }

    private static RecordingControlCommand createControl() {
        return new RecordingControlCommand(
                TAB,
                TOKEN
        );
    }
}
