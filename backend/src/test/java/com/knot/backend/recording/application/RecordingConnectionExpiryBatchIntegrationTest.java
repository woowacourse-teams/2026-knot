package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
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
class RecordingConnectionExpiryBatchIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final int BATCH_SIZE = 100;

    private final RecordingConnectionExpiryService recordingConnectionExpiryService;
    private final RecordingService recordingService;
    private final WorkspaceLeaveService workspaceLeaveService;
    private final JdbcClient jdbcClient;

    RecordingConnectionExpiryBatchIntegrationTest(
            RecordingConnectionExpiryService recordingConnectionExpiryService,
            RecordingService recordingService,
            WorkspaceLeaveService workspaceLeaveService,
            JdbcClient jdbcClient
    ) {
        this.recordingConnectionExpiryService = recordingConnectionExpiryService;
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
    @DisplayName("요청이 없는 녹음은 회수 작업이 실행 시각이 아니라 마지막 신호 + 30초로 종료한다")
    void expireDue_success_endsAtLastSeenPlusTimeout() {
        // given
        StartedRecording recording = startRecording(ownerFixture("회수 팀"));
        disconnect(
                recording.id(),
                600
        );
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();

        // when
        int expiredCount = recordingConnectionExpiryService.expireDue(BATCH_SIZE);

        // then
        RecordingRow row = recordingRow(recording.id());
        assertThat(expiredCount).isEqualTo(1);
        assertThat(row.status()).isEqualTo("ENDED");
        assertThat(row.endReason()).isEqualTo("CONNECTION_EXPIRED");
        assertThat(row.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
        assertThat(row.lastSeenAt()).isEqualTo(lastSeenAt);
    }

    @Test
    @DisplayName("생존 신호가 살아 있어도 누적 2시간에 닿은 녹음은 회수 작업이 상한 도달 시각과 상한 사유로 종료한다")
    void expireDue_success_endsAtMaxDuration() {
        // given
        StartedRecording recording = startRecording(ownerFixture("상한 팀"));
        rewindRecording(
                recording.id(),
                7_205,
                0L
        );
        RecordingRow before = recordingRow(recording.id());

        // when
        int expiredCount = recordingConnectionExpiryService.expireDue(BATCH_SIZE);

        // then
        RecordingRow row = recordingRow(recording.id());
        assertThat(expiredCount).isEqualTo(1);
        assertThat(row.status()).isEqualTo("ENDED");
        assertThat(row.endReason()).isEqualTo("MAX_DURATION");
        assertThat(row.endedAt()).isEqualTo(
                before.currentIntervalStartedAt()
                        .plusSeconds(7_200)
        );
        assertThat(row.accumulatedRecordingMillis()).isEqualTo(7_200_000L);
        assertThat(row.lastSeenAt()).isEqualTo(before.lastSeenAt());
    }

    @Test
    @DisplayName("일시정지를 뺀 누적 시간으로 상한을 판단해 남은 시간이 있는 녹음은 회수하지 않는다")
    void expireDue_success_excludesPauseFromMaxDuration() {
        // given
        StartedRecording remaining = startRecording(ownerFixture("잔여 팀"));
        rewindRecording(
                remaining.id(),
                5_340,
                1_800_000L
        );
        StartedRecording reached = startRecording(ownerFixture("도달 팀"));
        rewindRecording(
                reached.id(),
                5_410,
                1_800_000L
        );
        Instant reachedIntervalStartedAt = recordingRow(reached.id()).currentIntervalStartedAt();

        // when
        int expiredCount = recordingConnectionExpiryService.expireDue(BATCH_SIZE);

        // then
        assertThat(expiredCount).isEqualTo(1);
        assertThat(recordingRow(remaining.id()).status()).isEqualTo("RECORDING");
        RecordingRow row = recordingRow(reached.id());
        assertThat(row.endReason()).isEqualTo("MAX_DURATION");
        assertThat(row.endedAt()).isEqualTo(reachedIntervalStartedAt.plusSeconds(5_400));
        assertThat(row.accumulatedRecordingMillis()).isEqualTo(7_200_000L);
    }

    @Test
    @DisplayName("살아 있는 녹음과 이미 종료·폐기된 녹음은 회수 작업이 바꾸지 않는다")
    void expireDue_success_ignoresAliveAndTerminal() {
        // given
        Fixture alive = ownerFixture("진행 팀");
        StartedRecording aliveRecording = startRecording(alive);
        Fixture ended = ownerFixture("종료 팀");
        StartedRecording endedRecording = startRecording(ended);
        recordingService.end(
                ended.workspaceId(),
                ended.memberId(),
                endedRecording.id(),
                endedRecording.control()
        );
        disconnect(
                endedRecording.id(),
                600
        );
        long discardedRecordingId = discardedRecording();
        disconnect(
                discardedRecordingId,
                600
        );
        List<RecordingRow> before = List.of(
                recordingRow(aliveRecording.id()),
                recordingRow(endedRecording.id()),
                recordingRow(discardedRecordingId)
        );

        // when
        int expiredCount = recordingConnectionExpiryService.expireDue(BATCH_SIZE);

        // then
        assertThat(expiredCount).isZero();
        assertThat(
                List.of(
                        recordingRow(aliveRecording.id()),
                        recordingRow(endedRecording.id()),
                        recordingRow(discardedRecordingId)
                )
        ).isEqualTo(before);
    }

    @Test
    @DisplayName("회수 작업을 다시 실행해도 처음 확정한 종료 시각을 바꾸지 않는다")
    void expireDue_success_repeatedRunKeepsEndedAt() {
        // given
        StartedRecording recording = startRecording(ownerFixture("반복 팀"));
        disconnect(
                recording.id(),
                600
        );
        recordingConnectionExpiryService.expireDue(BATCH_SIZE);
        RecordingRow first = recordingRow(recording.id());

        // when
        int expiredCount = recordingConnectionExpiryService.expireDue(BATCH_SIZE);

        // then
        assertThat(expiredCount).isZero();
        assertThat(recordingRow(recording.id())).isEqualTo(first);
    }

    @Test
    @DisplayName("한 번에 정해진 개수만 회수하고 남은 녹음은 다음 실행에서 회수한다")
    void expireDue_success_limitsBatchSize() {
        // given
        List<Long> recordingIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            StartedRecording recording = startRecording(ownerFixture("배치 팀 " + i));
            disconnect(
                    recording.id(),
                    600 - i
            );
            recordingIds.add(recording.id());
        }

        // when
        List<Integer> expiredCounts = List.of(
                recordingConnectionExpiryService.expireDue(2),
                recordingConnectionExpiryService.expireDue(2)
        );

        // then
        assertThat(expiredCounts).containsExactly(
                2,
                1
        );
        assertThat(recordingIds)
                .allSatisfy(recordingId -> assertThat(recordingRow(recordingId).status()).isEqualTo("ENDED"));
    }

    @Test
    @DisplayName("회수 직전에 받은 유효 신호는 녹음을 유지하고 회수 뒤 늦은 신호는 종료 결과를 바꾸지 않는다")
    void expireDue_success_distinguishesSignalBeforeAndAfter() {
        // given
        Fixture keptFixture = ownerFixture("유지 팀");
        StartedRecording kept = startRecording(keptFixture);
        disconnect(
                kept.id(),
                29
        );
        heartbeat(
                keptFixture,
                kept
        );
        Fixture lateFixture = ownerFixture("늦은 신호 팀");
        StartedRecording late = startRecording(lateFixture);
        disconnect(
                late.id(),
                600
        );
        recordingConnectionExpiryService.expireDue(BATCH_SIZE);
        RecordingRow reclaimed = recordingRow(late.id());

        // when
        RecordingHeartbeatResult lateSignal = heartbeat(
                lateFixture,
                late
        );

        // then
        assertThat(recordingRow(kept.id()).status()).isEqualTo("RECORDING");
        assertThat(lateSignal.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(lateSignal.endedAt()).isEqualTo(reclaimed.endedAt());
        assertThat(recordingRow(late.id())).isEqualTo(reclaimed);
    }

    @Test
    @DisplayName("회수 작업과 신호·일시정지가 겹쳐도 교착 없이 한 종료 결과로 수렴한다")
    void expireDue_success_concurrentWithRequests() throws Exception {
        // given
        Fixture fixture = ownerFixture("경합 팀");
        StartedRecording recording = startRecording(fixture);
        disconnect(
                recording.id(),
                600
        );
        Instant lastSeenAt = recordingRow(recording.id()).lastSeenAt();

        // when
        List<Outcome> outcomes = runTogether(
                List.of(
                        () -> recordingConnectionExpiryService.expireDue(BATCH_SIZE),
                        () -> heartbeat(
                                fixture,
                                recording
                        ),
                        () -> recordingService.pause(
                                fixture.workspaceId(),
                                fixture.memberId(),
                                recording.id(),
                                recording.control()
                        )
                )
        );

        // then
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.unexpected()).isNull());
        RecordingRow row = recordingRow(recording.id());
        assertThat(row.status()).isEqualTo("ENDED");
        assertThat(row.endReason()).isEqualTo("CONNECTION_EXPIRED");
        assertThat(row.endedAt()).isEqualTo(lastSeenAt.plusSeconds(30));
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
            return new Outcome(null);
        } catch (ProjectException exception) {
            return new Outcome(null);
        } catch (RuntimeException exception) {
            return new Outcome(exception);
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

    private long discardedRecording() {
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("폐기 팀");
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
                new Fixture(
                        workspaceId,
                        memberId
                )
        );
        workspaceLeaveService.leave(
                memberId,
                workspaceId
        );
        return recording.id();
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

    private StartedRecording startRecording(Fixture fixture) {
        UUID tabId = UUID.randomUUID();
        long recordingId = recordingService.start(
                fixture.workspaceId(),
                fixture.memberId(),
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

    // 신호는 20초 전에 받아 연결은 살아 있고, 현재 녹음 구간만 오래 이어진 상태를 만든다
    private void rewindRecording(
            long recordingId,
            int currentIntervalSeconds,
            long accumulatedMillis
    ) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - make_interval(secs => :seconds),
                    current_interval_started_at = current_interval_started_at - make_interval(secs => :seconds),
                    last_seen_at = last_seen_at - INTERVAL '20 seconds',
                    accumulated_recording_millis = :accumulatedMillis
                WHERE id = :recordingId
                """)
                .param(
                        "seconds",
                        currentIntervalSeconds
                )
                .param(
                        "accumulatedMillis",
                        accumulatedMillis
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    // 실제 시간을 기다리지 않도록 녹음의 모든 시각을 같은 만큼 과거로 옮겨 신호가 끊긴 상태를 만든다
    private void disconnect(
            long recordingId,
            int seconds
    ) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - make_interval(secs => :seconds),
                    current_interval_started_at = current_interval_started_at - make_interval(secs => :seconds),
                    paused_at = paused_at - make_interval(secs => :seconds),
                    ended_at = ended_at - make_interval(secs => :seconds),
                    last_seen_at = last_seen_at - make_interval(secs => :seconds)
                WHERE id = :recordingId
                """)
                .param(
                        "seconds",
                        seconds
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
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
                SELECT status, current_interval_started_at, last_seen_at, ended_at, end_reason,
                    accumulated_recording_millis
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
                            OffsetDateTime currentIntervalStartedAt = resultSet.getObject(
                                    "current_interval_started_at",
                                    OffsetDateTime.class
                            );
                            OffsetDateTime endedAt = resultSet.getObject(
                                    "ended_at",
                                    OffsetDateTime.class
                            );
                            return new RecordingRow(
                                    resultSet.getString("status"),
                                    currentIntervalStartedAt == null ? null : currentIntervalStartedAt.toInstant(),
                                    resultSet.getObject(
                                            "last_seen_at",
                                            OffsetDateTime.class
                                    )
                                            .toInstant(),
                                    endedAt == null ? null : endedAt.toInstant(),
                                    resultSet.getString("end_reason"),
                                    resultSet.getLong("accumulated_recording_millis")
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
            Instant currentIntervalStartedAt,
            Instant lastSeenAt,
            Instant endedAt,
            String endReason,
            long accumulatedRecordingMillis
    ) {
    }

    private record Outcome(RuntimeException unexpected) {
    }
}
