package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingAudioChunkCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest(properties = "recording.audio-chunks.enabled=true")
class RecordingAudioChunkServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:10Z");
    private static final String HASH = Base64.getEncoder()
            .encodeToString(new byte[32]);
    private static final UUID TAB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final RecordingControlCommand CONTROL = createControl();

    @Autowired
    private RecordingAudioChunkService service;
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
    @DisplayName("같은 descriptor를 동시에 예약해도 조각 한 행과 생성 응답 하나로 수렴한다")
    void reserve_success_simultaneousIdenticalDescriptor() throws Exception {
        // given
        RecordingAudioChunkCommand command = command(
                1,
                HASH
        );

        // when
        List<Object> results = reserveConcurrently(
                command,
                command
        );

        // then
        assertThat(results).allMatch(RecordingAudioChunkReservationResult.class::isInstance);
        assertThat(
                results.stream()
                        .map(RecordingAudioChunkReservationResult.class::cast)
                        .filter(RecordingAudioChunkReservationResult::created)
                        .count()
        ).isEqualTo(1);
        assertThat(countChunks()).isEqualTo(1);
        assertThat(nextSequence()).isEqualTo(2);
        assertThat(lastSeenAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    @DisplayName("같은 번호의 다른 descriptor가 동시에 오면 하나만 저장하고 다른 요청은 충돌한다")
    void reserve_failure_simultaneousConflictingDescriptor() throws Exception {
        // given
        byte[] other = new byte[32];
        other[0] = 1;
        RecordingAudioChunkCommand first = command(
                1,
                HASH
        );
        RecordingAudioChunkCommand second = command(
                1,
                Base64.getEncoder()
                        .encodeToString(other)
        );

        // when
        List<Object> results = reserveConcurrently(
                first,
                second
        );

        // then
        assertThat(
                results.stream()
                        .filter(RecordingAudioChunkReservationResult.class::isInstance)
                        .count()
        ).isEqualTo(1);
        assertThat(
                results.stream()
                        .filter(RecordingException.class::isInstance)
                        .map(RecordingException.class::cast)
                        .map(RecordingException::getErrorCode)
                        .toList()
        ).containsExactly(RecordingErrorCode.AUDIO_CHUNK_CONFLICT);
        assertThat(countChunks()).isEqualTo(1);
        assertThat(nextSequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("두 번째 조각 완료는 prefix를 전진하지 않고 첫 조각이 완료되면 두 조각까지 전진한다")
    void complete_success_reverseOrder() {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        HASH
                )
        );
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        2,
                        HASH
                )
        );
        RecordingAudioChunkCompletionResult second = service.complete(
                workspaceId,
                memberId,
                recordingId,
                2,
                CONTROL
        );

        // when
        RecordingAudioChunkCompletionResult first = service.complete(
                workspaceId,
                memberId,
                recordingId,
                1,
                CONTROL
        );

        // then
        assertThat(second.verifiedThroughSequence()).isZero();
        assertThat(second.verifiedThroughFrame()).isZero();
        assertThat(first.verifiedThroughSequence()).isEqualTo(2);
        assertThat(first.verifiedThroughFrame()).isEqualTo(160000);
        assertThat(first.verifiedAudioMillis()).isEqualTo(10000);
        assertThat(lastSeenAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"freeze", "discard", "membershipLoss"})
    @DisplayName("HEAD 중 범위 고정이나 폐기나 소속 상실이 발생하면 VERIFIED ACK를 저장하지 않는다")
    void complete_failure_stateChangedDuringHead(String transition) {
        // given
        service.reserve(
                workspaceId,
                memberId,
                recordingId,
                command(
                        1,
                        HASH
                )
        );
        when(storage.findStoredObject(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            changeState(transition);
            return stored();
        });

        // when
        Throwable thrown = catchThrowable(
                () -> service.complete(
                        workspaceId,
                        memberId,
                        recordingId,
                        1,
                        CONTROL
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class);
        assertThat(chunkStatus()).isEqualTo("RESERVED");
        assertThat(
                jdbcClient.sql("SELECT storage_version_id FROM recording_audio_chunks WHERE recording_id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(String.class)
                        .optional()
        ).isEmpty();
    }

    @Test
    @DisplayName("만료된 세션의 예약을 거절해도 논리적 종료는 commit하고 조각은 생성하지 않는다")
    void reserve_failure_expiryCommitsEnded() {
        // given
        jdbcClient.sql("""
                UPDATE recording_sessions SET started_at = CAST(:past AS TIMESTAMPTZ),
                    current_interval_started_at = CAST(:past AS TIMESTAMPTZ), last_seen_at = CAST(:past AS TIMESTAMPTZ)
                WHERE id = :id
                """)
                .param(
                        "past",
                        NOW.minusSeconds(40)
                                .toString()
                )
                .param(
                        "id",
                        recordingId
                )
                .update();

        // when
        Throwable thrown = catchThrowable(
                () -> service.reserve(
                        workspaceId,
                        memberId,
                        recordingId,
                        command(
                                1,
                                HASH
                        )
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingAudioCaptureExpiredException.class);
        assertThat(
                jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("ENDED");
        assertThat(
                jdbcClient.sql("SELECT ended_at FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                recordingId
                        )
                        .query(Instant.class)
                        .single()
        ).isEqualTo(NOW.minusSeconds(10));
        assertThat(countChunks()).isZero();
        assertThat(nextSequence()).isEqualTo(1);
    }

    private List<Object> reserveConcurrently(
            RecordingAudioChunkCommand first,
            RecordingAudioChunkCommand second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> firstResult = executor.submit(
                    () -> reserveAfterBarrier(
                            first,
                            barrier
                    )
            );
            Future<Object> secondResult = executor.submit(
                    () -> reserveAfterBarrier(
                            second,
                            barrier
                    )
            );
            return List.of(
                    firstResult.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    secondResult.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
    }

    private Object reserveAfterBarrier(
            RecordingAudioChunkCommand command,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await(
                5,
                TimeUnit.SECONDS
        );
        try {
            return service.reserve(
                    workspaceId,
                    memberId,
                    recordingId,
                    command
            );
        } catch (RecordingException exception) {
            return exception;
        }
    }

    private RecordingAudioChunkCommand command(
            int sequence,
            String checksum
    ) {
        return new RecordingAudioChunkCommand(
                CONTROL,
                new AudioChunkDescriptor(
                        sequence,
                        (sequence - 1L) * 80000,
                        80000,
                        160000,
                        checksum
                )
        );
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

    private void changeState(String transition) {
        switch (transition) {
            case "freeze" -> jdbcClient.sql("UPDATE recording_audio_streams SET frozen = TRUE WHERE recording_id = :id")
                    .param(
                            "id",
                            recordingId
                    )
                    .update();
            case "discard" -> jdbcClient.sql("""
                    UPDATE recording_sessions SET status = 'DISCARDED', current_interval_started_at = NULL,
                        ended_at = CAST(:now AS TIMESTAMPTZ), last_seen_at = CAST(:now AS TIMESTAMPTZ)
                    WHERE id = :id
                    """)
                    .param(
                            "now",
                            NOW.toString()
                    )
                    .param(
                            "id",
                            recordingId
                    )
                    .update();
            case "membershipLoss" -> jdbcClient.sql("DELETE FROM workspace_members WHERE workspace_id = :id")
                    .param(
                            "id",
                            workspaceId
                    )
                    .update();
            default -> throw new IllegalArgumentException("알 수 없는 상태 전이입니다");
        }
    }

    private int countChunks() {
        return jdbcClient.sql("SELECT count(*) FROM recording_audio_chunks WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(Integer.class)
                .single();
    }

    private int nextSequence() {
        return jdbcClient.sql("SELECT next_sequence FROM recording_audio_streams WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(Integer.class)
                .single();
    }

    private String chunkStatus() {
        return jdbcClient.sql("SELECT status FROM recording_audio_chunks WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private Instant lastSeenAt() {
        return jdbcClient.sql("SELECT last_seen_at FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(Instant.class)
                .single();
    }
    private static RecordingControlCommand createControl() {
        return new RecordingControlCommand(
                TAB,
                TOKEN
        );
    }

}
