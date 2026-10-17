package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingPauseIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingService recordingService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;

    RecordingPauseIntegrationTest(
            RecordingService recordingService,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_invitations, workspace_members, workspaces,
                    oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("일시정지한 뒤 종료하면 일시정지 구간을 누적하지 않고 같은 누적 시간으로 종료한다")
    void pause_success_endAfterPauseKeepsElapsedTime() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        RecordingPauseResult paused = recordingService.pause(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );

        // when
        RecordingEndResult ended = recordingService.end(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );

        // then
        RecordingState state = recordingState(recording.id());
        assertThat(ended.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(state.accumulatedMillis()).isEqualTo(paused.elapsedMillis());
        assertThat(state.pausedAt()).isEqualTo(paused.pausedAt());
    }

    @Test
    @DisplayName("같은 녹음을 동시에 일시정지해도 하나의 일시정지 시각과 누적 시간으로 수렴한다")
    void pause_success_concurrentPausesConverge() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("동시 일시정지 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        RaceResult<RecordingPauseResult, RecordingPauseResult> result = race(
                () -> recordingService.pause(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                ),
                () -> recordingService.pause(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                )
        );

        // then
        RecordingState state = recordingState(recording.id());
        assertThat(
                result.first()
                        .value()
                        .pausedAt()
        ).isEqualTo(state.pausedAt());
        assertThat(
                result.second()
                        .value()
                        .pausedAt()
        ).isEqualTo(state.pausedAt());
        assertThat(
                result.first()
                        .value()
                        .elapsedMillis()
        ).isEqualTo(state.accumulatedMillis());
        assertThat(
                result.second()
                        .value()
                        .elapsedMillis()
        ).isEqualTo(state.accumulatedMillis());
    }

    @Test
    @DisplayName("일시정지와 종료가 경합해도 ENDED를 PAUSED로 되돌리지 않는다")
    void pause_successOrRejected_whenRacingWithEnd() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("일시정지 종료 경합 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        RaceResult<RecordingPauseResult, RecordingEndResult> result = race(
                () -> recordingService.pause(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                ),
                () -> recordingService.end(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                )
        );

        // then
        assertThat(
                result.second()
                        .errorCode()
        ).isNull();
        assertThat(recordingState(recording.id()).status()).isEqualTo("ENDED");
        if (result.first()
                .errorCode() != null) {
            assertThat(
                    result.first()
                            .errorCode()
            ).isEqualTo(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        } else {
            assertThat(recordingState(recording.id()).accumulatedMillis()).isEqualTo(
                    result.first()
                            .value()
                            .elapsedMillis()
            );
        }
    }

    @Test
    @DisplayName("일시정지 중에는 활성 녹음 제한이 유지되어 새 녹음을 시작할 수 없다")
    void pause_success_keepsActiveRecordingLimit() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("활성 제한 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        recordingService.pause(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );

        // when
        Throwable thrown = catchThrowable(
                () -> recordingService.start(
                        workspaceId,
                        memberId,
                        new RecordingStartCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                CONTROL_TOKEN
                        )
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("일시정지 후 같은 트랜잭션에서 예외가 나면 일시정지를 롤백한다")
    void pause_failure_rollsBackWhenOuterTransactionFails() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("롤백 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            recordingService.pause(
                    workspaceId,
                    memberId,
                    recording.id(),
                    recording.control()
            );
            throw new IllegalStateException("일시정지 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        RecordingState state = recordingState(recording.id());
        assertThat(state.status()).isEqualTo("RECORDING");
        assertThat(state.pausedAt()).isNull();
    }

    private <T, U> RaceResult<T, U> race(
            Callable<T> first,
            Callable<U> second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<T>> firstResult = executorService.submit(
                    outcomeAfterBarrier(
                            barrier,
                            first
                    )
            );
            Future<Outcome<U>> secondResult = executorService.submit(
                    outcomeAfterBarrier(
                            barrier,
                            second
                    )
            );
            return new RaceResult<>(
                    firstResult.get(
                            10,
                            TimeUnit.SECONDS
                    ),
                    secondResult.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        } finally {
            executorService.shutdownNow();
        }
    }

    private <T> Callable<Outcome<T>> outcomeAfterBarrier(
            CyclicBarrier barrier,
            Callable<T> operation
    ) {
        return () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            try {
                return new Outcome<>(
                        operation.call(),
                        null
                );
            } catch (ProjectException exception) {
                return new Outcome<>(
                        null,
                        exception.getErrorCode()
                );
            }
        };
    }

    private StartedRecording startRecording(
            long workspaceId,
            long memberId
    ) {
        UUID tabId = UUID.randomUUID();
        long recordingId = recordingService.start(
                workspaceId,
                memberId,
                new RecordingStartCommand(
                        UUID.randomUUID(),
                        tabId,
                        CONTROL_TOKEN
                )
        )
                .recordingId();
        return new StartedRecording(
                recordingId,
                new RecordingControlCommand(
                        tabId,
                        CONTROL_TOKEN
                )
        );
    }

    private long saveMember(String nickname) {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES (:nickname, NULL)
                RETURNING id
                """)
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    private long saveWorkspace(String name) {
        return jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES (:name, CAST(:createdAt AS TIMESTAMPTZ))
                RETURNING id
                """)
                .param(
                        "name",
                        name
                )
                .param(
                        "createdAt",
                        CREATED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, :role, CAST(:joinedAt AS TIMESTAMPTZ))
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
                        "role",
                        role
                )
                .param(
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .update();
    }

    private RecordingState recordingState(long recordingId) {
        return jdbcClient.sql("""
                SELECT status, paused_at, ended_at, accumulated_recording_millis
                FROM recording_sessions
                WHERE id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> {
                            OffsetDateTime pausedAt = resultSet.getObject(
                                    "paused_at",
                                    OffsetDateTime.class
                            );
                            OffsetDateTime endedAt = resultSet.getObject(
                                    "ended_at",
                                    OffsetDateTime.class
                            );
                            return new RecordingState(
                                    resultSet.getString("status"),
                                    pausedAt == null ? null : pausedAt.toInstant(),
                                    endedAt == null ? null : endedAt.toInstant(),
                                    resultSet.getLong("accumulated_recording_millis")
                            );
                        }
                )
                .single();
    }

    private record StartedRecording(
            long id,
            RecordingControlCommand control
    ) {
    }

    private record RecordingState(
            String status,
            Instant pausedAt,
            Instant endedAt,
            long accumulatedMillis
    ) {
    }

    private record Outcome<T>(
            T value,
            ErrorCode errorCode
    ) {
    }

    private record RaceResult<T, U>(
            Outcome<T> first,
            Outcome<U> second
    ) {
    }
}
