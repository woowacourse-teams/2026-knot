package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.application.WorkspaceLeaveService;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
class RecordingEndIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingService recordingService;
    private final WorkspaceLeaveService workspaceLeaveService;
    private final RecordingControlTokenHasher recordingControlTokenHasher;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;

    RecordingEndIntegrationTest(
            RecordingService recordingService,
            WorkspaceLeaveService workspaceLeaveService,
            RecordingControlTokenHasher recordingControlTokenHasher,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
        this.workspaceLeaveService = workspaceLeaveService;
        this.recordingControlTokenHasher = recordingControlTokenHasher;
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
    @DisplayName("일시정지 중 종료하면 일시정지 구간을 누적하지 않고 종료 시각을 기록한다")
    void end_success_pausedRecordingKeepsAccumulatedTime() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        RecordingControlCommand control = control(UUID.randomUUID());
        long recordingId = savePausedRecording(
                workspaceId,
                memberId,
                control,
                30_000L
        );

        // when
        RecordingEndResult result = recordingService.end(
                workspaceId,
                memberId,
                recordingId,
                control
        );

        // then
        RecordingState state = recordingState(recordingId);
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(state.status()).isEqualTo("ENDED");
        assertThat(state.endedAt()).isEqualTo(result.endedAt());
        assertThat(state.accumulatedMillis()).isEqualTo(30_000L);
        assertThat(endReason(recordingId)).isEqualTo("USER_ENDED");
    }

    @Test
    @DisplayName("같은 녹음을 동시에 종료해도 하나의 종료 시각으로 수렴한다")
    void end_success_concurrentEndsConverge() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("동시 종료 팀");
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
        RaceResult<RecordingEndResult, RecordingEndResult> result = race(
                () -> recordingService.end(
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
                result.first()
                        .errorCode()
        ).isNull();
        assertThat(
                result.second()
                        .errorCode()
        ).isNull();
        Instant endedAt = recordingState(recording.id()).endedAt();
        assertThat(
                result.first()
                        .value()
                        .endedAt()
        ).isEqualTo(endedAt);
        assertThat(
                result.second()
                        .value()
                        .endedAt()
        ).isEqualTo(endedAt);
    }

    @Test
    @DisplayName("종료와 본인 탈퇴가 경합하면 종료 후 보존되거나 탈퇴 후 종료가 거절되고 폐기된다")
    void end_successOrRejected_whenRacingWithLeave() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("종료 탈퇴 경합 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        RaceResult<RecordingEndResult, Void> result = race(
                () -> recordingService.end(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                ),
                () -> {
                    workspaceLeaveService.leave(
                            memberId,
                            workspaceId
                    );
                    return null;
                }
        );

        // then
        assertThat(
                result.second()
                        .errorCode()
        ).isNull();
        String status = recordingState(recording.id()).status();
        if (result.first()
                .errorCode() == null) {
            assertThat(status).isEqualTo("ENDED");
        } else {
            assertThat(
                    result.first()
                            .errorCode()
            ).isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
            assertThat(status).isEqualTo("DISCARDED");
        }
    }

    @Test
    @DisplayName("종료한 뒤에는 새 요청으로 다음 녹음을 시작할 수 있다")
    void end_success_allowsNextRecording() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("다음 녹음 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        recordingService.end(
                workspaceId,
                memberId,
                recording.id(),
                recording.control()
        );

        // when
        RecordingStartResult next = recordingService.start(
                workspaceId,
                memberId,
                startCommand(UUID.randomUUID())
        );

        // then
        assertThat(next.created()).isTrue();
        assertThat(next.recordingId()).isNotEqualTo(recording.id());
        assertThat(recordingState(recording.id()).status()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("같은 회원의 다른 탭이 종료하면 거절하고 녹음 상태를 바꾸지 않는다")
    void end_failure_otherTabKeepsRecording() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("다른 탭 팀");
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
        Throwable thrown = catchThrowable(
                () -> recordingService.end(
                        workspaceId,
                        memberId,
                        recording.id(),
                        control(UUID.randomUUID())
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_CONTROL_DENIED);
        RecordingState state = recordingState(recording.id());
        assertThat(state.status()).isEqualTo("RECORDING");
        assertThat(state.endedAt()).isNull();
    }

    @Test
    @DisplayName("탈퇴한 시작자가 최초 탭 증명으로 종료해도 거절하고 폐기 상태를 유지한다")
    void end_failure_leftMemberKeepsDiscarded() {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("탈퇴 후 종료 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        workspaceLeaveService.leave(
                memberId,
                workspaceId
        );

        // when
        Throwable thrown = catchThrowable(
                () -> recordingService.end(
                        workspaceId,
                        memberId,
                        recording.id(),
                        recording.control()
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
        assertThat(recordingState(recording.id()).status()).isEqualTo("DISCARDED");
    }

    @Test
    @DisplayName("종료 후 같은 트랜잭션에서 예외가 나면 종료를 롤백한다")
    void end_failure_rollsBackWhenOuterTransactionFails() {
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
            recordingService.end(
                    workspaceId,
                    memberId,
                    recording.id(),
                    recording.control()
            );
            throw new IllegalStateException("종료 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        RecordingState state = recordingState(recording.id());
        assertThat(state.status()).isEqualTo("RECORDING");
        assertThat(state.endedAt()).isNull();
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
                startCommand(tabId)
        )
                .recordingId();
        return new StartedRecording(
                recordingId,
                control(tabId)
        );
    }

    private RecordingStartCommand startCommand(UUID tabId) {
        return new RecordingStartCommand(
                UUID.randomUUID(),
                tabId,
                CONTROL_TOKEN
        );
    }

    private RecordingControlCommand control(UUID tabId) {
        return new RecordingControlCommand(
                tabId,
                CONTROL_TOKEN
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

    private long savePausedRecording(
            long workspaceId,
            long memberId,
            RecordingControlCommand control,
            long accumulatedMillis
    ) {
        Instant pausedAt = recentPausedAt();
        return jdbcClient.sql("""
                INSERT INTO recording_sessions (
                    workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                    started_at, current_interval_started_at, ended_at, last_seen_at, accumulated_recording_millis
                )
                VALUES (
                    :workspaceId, :memberId, gen_random_uuid(), :tabId, :controlTokenHash, 'PAUSED',
                    CAST(:startedAt AS TIMESTAMPTZ), NULL, NULL, CAST(:pausedAt AS TIMESTAMPTZ), :accumulatedMillis
                )
                RETURNING id
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
                        "tabId",
                        control.tabId()
                )
                .param(
                        "controlTokenHash",
                        recordingControlTokenHasher.hash(control.controlToken())
                )
                .param(
                        "startedAt",
                        pausedAt.minusMillis(accumulatedMillis)
                                .toString()
                )
                .param(
                        "pausedAt",
                        pausedAt.toString()
                )
                .param(
                        "accumulatedMillis",
                        accumulatedMillis
                )
                .query(Long.class)
                .single();
    }

    // 연결 만료가 아닌 사용자 종료를 검증하도록 마지막 신호를 방금 전으로 둔다
    private Instant recentPausedAt() {
        return Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(10);
    }

    private String endReason(long recordingId) {
        return jdbcClient.sql("SELECT end_reason FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private RecordingState recordingState(long recordingId) {
        return jdbcClient.sql("""
                SELECT status, ended_at, accumulated_recording_millis
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
                            OffsetDateTime endedAt = resultSet.getObject(
                                    "ended_at",
                                    OffsetDateTime.class
                            );
                            return new RecordingState(
                                    resultSet.getString("status"),
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
