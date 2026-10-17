package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.RecordingResumeResult;
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
class RecordingResumeIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingService recordingService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;

    RecordingResumeIntegrationTest(
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
    @DisplayName("일시정지 후 재개하면 일시정지 시각을 비우고 누적 시간을 유지한 채 녹음을 다시 센다")
    void resume_success_afterPause() {
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
        RecordingPauseResult paused = pause(
                workspaceId,
                memberId,
                recording
        );

        // when
        RecordingResumeResult resumed = resume(
                workspaceId,
                memberId,
                recording
        );

        // then
        RecordingState state = recordingState(recording.id());
        assertThat(resumed.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(resumed.elapsedMillis()).isEqualTo(paused.elapsedMillis());
        assertThat(resumed.resumedAt()).isAfterOrEqualTo(paused.pausedAt());
        assertThat(state.status()).isEqualTo("RECORDING");
        assertThat(state.pausedAt()).isNull();
        assertThat(state.accumulatedMillis()).isEqualTo(paused.elapsedMillis());
    }

    @Test
    @DisplayName("같은 녹음을 동시에 재개해도 하나의 재개 시각으로 수렴한다")
    void resume_success_concurrentResumesConverge() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("동시 재개 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        pause(
                workspaceId,
                memberId,
                recording
        );

        // when
        RaceResult<RecordingResumeResult, RecordingResumeResult> result = race(
                () -> resume(
                        workspaceId,
                        memberId,
                        recording
                ),
                () -> resume(
                        workspaceId,
                        memberId,
                        recording
                )
        );

        // then
        assertThat(
                result.first()
                        .value()
                        .resumedAt()
        ).isEqualTo(
                result.second()
                        .value()
                        .resumedAt()
        );
        assertThat(
                result.first()
                        .value()
                        .elapsedMillis()
        ).isEqualTo(
                result.second()
                        .value()
                        .elapsedMillis()
        );
        assertThat(recordingState(recording.id()).status()).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("일시정지와 재개가 경합해도 둘 다 성공하고 누적 시간을 중복 합산하지 않는다")
    void resume_success_whenRacingWithPause() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("일시정지 재개 경합 팀");
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
        RaceResult<RecordingPauseResult, RecordingResumeResult> result = race(
                () -> pause(
                        workspaceId,
                        memberId,
                        recording
                ),
                () -> resume(
                        workspaceId,
                        memberId,
                        recording
                )
        );

        // then
        assertThat(
                result.first()
                        .errorCode()
        ).isNull();
        assertThat(
                result.second()
                        .errorCode()
        ).isNull();
        RecordingState state = recordingState(recording.id());
        assertThat(state.status()).isIn(
                "RECORDING",
                "PAUSED"
        );
        assertThat(state.accumulatedMillis()).isEqualTo(
                result.first()
                        .value()
                        .elapsedMillis()
        );
    }

    @Test
    @DisplayName("재개와 종료가 경합해도 ENDED를 RECORDING으로 되돌리지 않는다")
    void resume_successOrRejected_whenRacingWithEnd() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("재개 종료 경합 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        pause(
                workspaceId,
                memberId,
                recording
        );

        // when
        RaceResult<RecordingResumeResult, RecordingEndResult> result = race(
                () -> resume(
                        workspaceId,
                        memberId,
                        recording
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
        }
    }

    @Test
    @DisplayName("재개 후 같은 트랜잭션에서 예외가 나면 재개를 롤백한다")
    void resume_failure_rollsBackWhenOuterTransactionFails() {
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
        RecordingPauseResult paused = pause(
                workspaceId,
                memberId,
                recording
        );

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            resume(
                    workspaceId,
                    memberId,
                    recording
            );
            throw new IllegalStateException("재개 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        RecordingState state = recordingState(recording.id());
        assertThat(state.status()).isEqualTo("PAUSED");
        assertThat(state.pausedAt()).isEqualTo(paused.pausedAt());
    }

    private RecordingPauseResult pause(
            long workspaceId,
            long memberId,
            StartedRecording recording
    ) {
        return recordingService.pause(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );
    }

    private RecordingResumeResult resume(
            long workspaceId,
            long memberId,
            StartedRecording recording
    ) {
        return recordingService.resume(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );
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
