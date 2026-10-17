package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
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
class RecordingCurrentServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingService recordingService;
    private final JdbcClient jdbcClient;

    RecordingCurrentServiceIntegrationTest(
            RecordingService recordingService,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
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
    @DisplayName("진행 중인 녹음을 조회해도 생존 시각, 누적 시간, 탭 제어 증명 저장값이 바뀌지 않는다")
    void findCurrent_success_recordingReadDoesNotChangeState() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        StoredRecording before = storedRecording(recording.id());

        // when
        Optional<RecordingCurrentResult> result = recordingService.findCurrent(
                workspaceId,
                memberId
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.recordingId()).isEqualTo(recording.id());
            assertThat(current.status()).isEqualTo(RecordingStatus.RECORDING);
            assertThat(current.startedAt()).isEqualTo(before.startedAt());
        });
        assertThat(storedRecording(recording.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("일시정지한 녹음을 반복 조회하면 일시정지 시점의 누적 시간을 그대로 반환하고 저장값을 바꾸지 않는다")
    void findCurrent_success_repeatedPausedReadKeepsElapsedTime() {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
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
        StoredRecording before = storedRecording(recording.id());
        recordingService.findCurrent(
                workspaceId,
                memberId
        );

        // when
        Optional<RecordingCurrentResult> result = recordingService.findCurrent(
                workspaceId,
                memberId
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(RecordingStatus.PAUSED);
            assertThat(current.elapsedMillis()).isEqualTo(paused.elapsedMillis());
        });
        assertThat(storedRecording(recording.id())).isEqualTo(before);
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
            long memberId
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'OWNER', CAST(:joinedAt AS TIMESTAMPTZ))
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
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .update();
    }

    private StoredRecording storedRecording(long recordingId) {
        return jdbcClient.sql("""
                SELECT status, tab_id, control_token_hash, started_at, last_seen_at, accumulated_recording_millis
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
                        ) -> new StoredRecording(
                                resultSet.getString("status"),
                                resultSet.getObject(
                                        "tab_id",
                                        UUID.class
                                ),
                                resultSet.getString("control_token_hash"),
                                resultSet.getObject(
                                        "started_at",
                                        OffsetDateTime.class
                                )
                                        .toInstant(),
                                resultSet.getObject(
                                        "last_seen_at",
                                        OffsetDateTime.class
                                )
                                        .toInstant(),
                                resultSet.getLong("accumulated_recording_millis")
                        )
                )
                .single();
    }

    private record StartedRecording(
            long id,
            RecordingControlCommand control
    ) {
    }

    private record StoredRecording(
            String status,
            UUID tabId,
            String controlTokenHash,
            Instant startedAt,
            Instant lastSeenAt,
            long accumulatedMillis
    ) {
    }
}
