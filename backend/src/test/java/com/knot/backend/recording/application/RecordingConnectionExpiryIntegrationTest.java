package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.application.WorkspaceLeaveService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingConnectionExpiryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final int DISCONNECTED_SECONDS = 300;
    private static final int TASK_COUNT = 3;

    private final RecordingService recordingService;
    private final WorkspaceLeaveService workspaceLeaveService;
    private final JdbcClient jdbcClient;

    RecordingConnectionExpiryIntegrationTest(
            RecordingService recordingService,
            WorkspaceLeaveService workspaceLeaveService,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
        this.workspaceLeaveService = workspaceLeaveService;
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
    @DisplayName("연결이 끊긴 녹음의 일시정지는 409로 거절해도 연결 만료 종료가 커밋되고 마지막 신호 시각은 그대로다")
    void pause_failure_expiryStaysCommitted() {
        // given
        Fixture fixture = ownerFixture("일시정지 만료 팀");
        StartedRecording recording = startRecording(
                fixture.workspaceId(),
                fixture.memberId()
        );
        disconnect(recording.id());
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();

        // when
        Throwable thrown = catchThrowable(
                () -> recordingService.pause(
                        fixture.workspaceId(),
                        fixture.memberId(),
                        recording.id(),
                        recording.control()
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        RecordingRow row = recordingRow(recording.id());
        assertThat(row.status()).isEqualTo("ENDED");
        assertThat(row.endReason()).isEqualTo("CONNECTION_EXPIRED");
        assertThat(row.lastSeenAt()).isEqualTo(lastSeenAt);
        assertThat(row.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
    }

    @Test
    @DisplayName("일시정지 중 연결이 끊긴 녹음의 재개는 409로 거절해도 연결 만료 종료가 커밋된다")
    void resume_failure_expiryStaysCommitted() {
        // given
        Fixture fixture = ownerFixture("재개 만료 팀");
        StartedRecording recording = startRecording(
                fixture.workspaceId(),
                fixture.memberId()
        );
        recordingService.pause(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id(),
                recording.control()
        );
        disconnect(recording.id());
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();

        // when
        Throwable thrown = catchThrowable(
                () -> recordingService.resume(
                        fixture.workspaceId(),
                        fixture.memberId(),
                        recording.id(),
                        recording.control()
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_ALREADY_ENDED);
        RecordingRow row = recordingRow(recording.id());
        assertThat(row.status()).isEqualTo("ENDED");
        assertThat(row.endReason()).isEqualTo("CONNECTION_EXPIRED");
        assertThat(row.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
    }

    @Test
    @DisplayName("생존 신호는 마지막 신호 시각을 갱신하고 만료 뒤 늦게 도착한 신호는 종료 상태만 돌려준다")
    void heartbeat_success_lateSignalDoesNotRevive() {
        // given
        Fixture fixture = ownerFixture("신호 팀");
        StartedRecording recording = startRecording(
                fixture.workspaceId(),
                fixture.memberId()
        );
        RecordingHeartbeatResult alive = heartbeat(
                fixture,
                recording
        );
        disconnect(recording.id());
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();
        heartbeat(
                fixture,
                recording
        );

        // when
        RecordingHeartbeatResult late = heartbeat(
                fixture,
                recording
        );

        // then
        assertThat(alive.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(alive.expiresAt()).isEqualTo(
                alive.lastSeenAt()
                        .plusSeconds(30)
        );
        assertThat(late.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(late.endReason()).isEqualTo(RecordingEndReason.CONNECTION_EXPIRED);
        assertThat(late.lastSeenAt()).isEqualTo(lastSeenAt);
        assertThat(late.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
        assertThat(late.expiresAt()).isNull();
        assertThat(recordingRow(recording.id()).lastSeenAt()).isEqualTo(lastSeenAt);
    }

    @Test
    @DisplayName("연결이 끊긴 녹음을 최초 탭이 종료하면 요청 시각이 아니라 만료 시각으로 종료한다")
    void end_success_disconnectedEndsAtExpiry() {
        // given
        Fixture fixture = ownerFixture("종료 만료 팀");
        StartedRecording recording = startRecording(
                fixture.workspaceId(),
                fixture.memberId()
        );
        disconnect(recording.id());
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();

        // when
        RecordingEndResult result = recordingService.end(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id(),
                recording.control()
        );

        // then
        assertThat(result.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
        assertThat(recordingRow(recording.id()).endReason()).isEqualTo("CONNECTION_EXPIRED");
    }

    @Test
    @DisplayName("다른 Workspace에 남은 본인 녹음의 연결이 끊겼으면 회수하고 새 녹음을 시작한다")
    void start_success_reclaimsDisconnectedInOtherWorkspace() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("새 팀");
        long otherWorkspaceId = saveWorkspace("이전 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording previous = startRecording(
                otherWorkspaceId,
                memberId
        );
        disconnect(previous.id());

        // when
        RecordingStartResult result = recordingService.start(
                workspaceId,
                memberId,
                startCommand(UUID.randomUUID())
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(recordingRow(previous.id()).status()).isEqualTo("ENDED");
        assertThat(recordingRow(previous.id()).endReason()).isEqualTo("CONNECTION_EXPIRED");
        assertThat(activeRecordingCount(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 Workspace의 본인 녹음 연결이 살아 있으면 새 시작을 409로 막는다")
    void start_failure_aliveInOtherWorkspace() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("새 팀");
        long otherWorkspaceId = saveWorkspace("이전 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId,
                "OWNER"
        );
        StartedRecording previous = startRecording(
                otherWorkspaceId,
                memberId
        );

        // when
        Throwable thrown = catchThrowable(
                () -> recordingService.start(
                        workspaceId,
                        memberId,
                        startCommand(UUID.randomUUID())
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
        assertThat(recordingRow(previous.id()).status()).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("연결이 끊긴 녹음의 시작 요청을 다시 보내면 만료를 저장하고 종료 상태를 돌려준다")
    void start_success_replayExpiresDisconnected() {
        // given
        Fixture fixture = ownerFixture("재요청 팀");
        UUID tabId = UUID.randomUUID();
        RecordingStartCommand command = startCommand(tabId);
        long recordingId = recordingService.start(
                fixture.workspaceId(),
                fixture.memberId(),
                command
        )
                .recordingId();
        disconnect(recordingId);
        Instant lastSeenAt = recordingRow(recordingId).lastSeenAt();

        // when
        RecordingStartResult result = recordingService.start(
                fixture.workspaceId(),
                fixture.memberId(),
                command
        );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(recordingRow(recordingId).lastSeenAt()).isEqualTo(lastSeenAt);
        assertThat(recordingRow(recordingId).endReason()).isEqualTo("CONNECTION_EXPIRED");
    }

    @Test
    @DisplayName("다른 Workspace의 새 시작과 끊긴 녹음의 신호·탈퇴가 겹쳐도 교착 없이 활성 녹음은 하나 이하다")
    void start_success_concurrentWithHeartbeatAndLeave() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("새 팀");
        long otherWorkspaceId = saveWorkspace("이전 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId,
                "MEMBER"
        );
        StartedRecording previous = startRecording(
                otherWorkspaceId,
                memberId
        );
        disconnect(previous.id());

        // when
        List<Outcome> outcomes = runTogether(
                List.of(
                        () -> recordingService.start(
                                workspaceId,
                                memberId,
                                startCommand(UUID.randomUUID())
                        ),
                        () -> recordingService.heartbeat(
                                otherWorkspaceId,
                                memberId,
                                previous.id(),
                                previous.control()
                        ),
                        () -> {
                            workspaceLeaveService.leave(
                                    memberId,
                                    otherWorkspaceId
                            );
                            return null;
                        }
                )
        );

        // then
        assertThat(outcomes).hasSize(TASK_COUNT)
                .allSatisfy(outcome -> assertThat(outcome.unexpected()).isNull());
        assertThat(
                outcomes.get(0)
                        .errorCode()
        ).isNull();
        assertThat(recordingRow(previous.id()).status()).isIn(
                "ENDED",
                "DISCARDED"
        );
        assertThat(activeRecordingCount(memberId)).isEqualTo(1);
    }

    private List<Outcome> runTogether(List<Callable<?>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService executorService = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(
                        executorService.submit(
                                () -> runAfterBarrier(
                                        barrier,
                                        task
                                )
                        )
                );
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(
                        future.get(
                                10,
                                TimeUnit.SECONDS
                        )
                );
            }
            return outcomes;
        } finally {
            executorService.shutdownNow();
        }
    }

    private Outcome runAfterBarrier(
            CyclicBarrier barrier,
            Callable<?> task
    ) throws Exception {
        barrier.await(
                5,
                TimeUnit.SECONDS
        );
        try {
            task.call();
            return new Outcome(
                    null,
                    null
            );
        } catch (ProjectException exception) {
            return new Outcome(
                    exception.getErrorCode()
                            .getCode(),
                    null
            );
        } catch (RuntimeException exception) {
            return new Outcome(
                    null,
                    exception
            );
        }
    }

    private RecordingHeartbeatResult heartbeat(
            Fixture fixture,
            StartedRecording recording
    ) {
        return recordingService.heartbeat(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id(),
                recording.control()
        );
    }

    private Fixture ownerFixture(String workspaceName) {
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace(workspaceName);
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        return new Fixture(
                workspaceId,
                memberId
        );
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
                new RecordingControlCommand(
                        tabId,
                        CONTROL_TOKEN
                )
        );
    }

    private RecordingStartCommand startCommand(UUID tabId) {
        return new RecordingStartCommand(
                UUID.randomUUID(),
                tabId,
                CONTROL_TOKEN
        );
    }

    // 실제 30초를 기다리지 않도록 녹음의 모든 시각을 같은 만큼 과거로 옮겨 신호가 끊긴 상태를 만든다
    private void disconnect(long recordingId) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - make_interval(secs => :seconds),
                    current_interval_started_at = current_interval_started_at - make_interval(secs => :seconds),
                    paused_at = paused_at - make_interval(secs => :seconds),
                    last_seen_at = last_seen_at - make_interval(secs => :seconds)
                WHERE id = :recordingId
                """)
                .param(
                        "seconds",
                        DISCONNECTED_SECONDS
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    private long activeRecordingCount(long memberId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM recording_sessions
                WHERE member_id = :memberId AND status IN ('RECORDING', 'PAUSED')
                """)
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .single();
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

    private RecordingRow recordingRow(long recordingId) {
        return jdbcClient.sql("""
                SELECT status, last_seen_at, ended_at, end_reason
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
                            return new RecordingRow(
                                    resultSet.getString("status"),
                                    resultSet.getObject(
                                            "last_seen_at",
                                            OffsetDateTime.class
                                    )
                                            .toInstant(),
                                    endedAt == null ? null : endedAt.toInstant(),
                                    resultSet.getString("end_reason")
                            );
                        }
                )
                .single();
    }

    private record Fixture(
            long workspaceId,
            long memberId
    ) {
    }

    private record StartedRecording(
            long id,
            RecordingControlCommand control
    ) {
    }

    private record RecordingRow(
            String status,
            Instant lastSeenAt,
            Instant endedAt,
            String endReason
    ) {
    }

    private record Outcome(
            String errorCode,
            RuntimeException unexpected
    ) {
    }
}
