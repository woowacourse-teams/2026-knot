package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.TeammateRecordingResult;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class TeammateRecordingServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final TeammateRecordingService teammateRecordingService;
    private final RecordingService recordingService;
    private final JdbcClient jdbcClient;

    TeammateRecordingServiceIntegrationTest(
            TeammateRecordingService teammateRecordingService,
            RecordingService recordingService,
            JdbcClient jdbcClient
    ) {
        this.teammateRecordingService = teammateRecordingService;
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
    @DisplayName("팀원의 녹음 중, 일시정지 녹음을 시작 시각 최신순으로 닉네임 이름과 함께 모두 반환하고 내 녹음은 뺀다")
    void findInProgress_success_teammatesOnly() {
        // given
        long workspaceId = saveWorkspace("팀");
        long me = joinedMember(
                workspaceId,
                "유월"
        );
        long ludens = joinedMember(
                workspaceId,
                "루덴스"
        );
        long bear = joinedMember(
                workspaceId,
                "흑곰"
        );
        startRecording(
                workspaceId,
                me
        );
        StartedRecording paused = startRecording(
                workspaceId,
                bear
        );
        RecordingPauseResult pauseResult = recordingService.pause(
                workspaceId,
                bear,
                paused.id(),
                paused.control()
        );
        StartedRecording recording = startRecording(
                workspaceId,
                ludens
        );

        // when
        List<TeammateRecordingResult> result = teammateRecordingService.findInProgress(
                workspaceId,
                me
        );

        // then
        assertThat(result).extracting(TeammateRecordingResult::recordingId)
                .containsExactly(
                        recording.id(),
                        paused.id()
                );
        assertThat(
                result.getFirst()
                        .status()
        ).isEqualTo(CurrentRecordingStatus.RECORDING);
        assertThat(
                result.getFirst()
                        .title()
        ).isEqualTo("루덴스 님의 녹음");
        assertThat(
                result.getLast()
                        .status()
        ).isEqualTo(CurrentRecordingStatus.PAUSED);
        assertThat(
                result.getLast()
                        .title()
        ).isEqualTo("흑곰 님의 녹음");
        assertThat(
                result.getLast()
                        .elapsedMillis()
        ).isEqualTo(pauseResult.elapsedMillis());
    }

    @Test
    @DisplayName("끝난 녹음과 다른 워크스페이스의 녹음은 반환하지 않는다")
    void findInProgress_success_excludesEndedAndOtherWorkspace() {
        // given
        long workspaceId = saveWorkspace("팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        long me = joinedMember(
                workspaceId,
                "유월"
        );
        long ludens = joinedMember(
                workspaceId,
                "루덴스"
        );
        long outsider = joinedMember(
                otherWorkspaceId,
                "이스타"
        );
        StartedRecording ended = startRecording(
                workspaceId,
                ludens
        );
        recordingService.end(
                workspaceId,
                ludens,
                ended.id(),
                ended.control()
        );
        startRecording(
                otherWorkspaceId,
                outsider
        );

        // when
        List<TeammateRecordingResult> result = teammateRecordingService.findInProgress(
                workspaceId,
                me
        );

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("조회해도 팀원 녹음의 생존 시각과 누적 시간이 바뀌지 않는다")
    void findInProgress_success_readDoesNotChangeState() {
        // given
        long workspaceId = saveWorkspace("팀");
        long me = joinedMember(
                workspaceId,
                "유월"
        );
        long ludens = joinedMember(
                workspaceId,
                "루덴스"
        );
        StartedRecording recording = startRecording(
                workspaceId,
                ludens
        );
        Map<String, Object> before = storedRecording(recording.id());

        // when
        teammateRecordingService.findInProgress(
                workspaceId,
                me
        );

        // then
        assertThat(storedRecording(recording.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("워크스페이스 소속이 아니면 거부한다")
    void findInProgress_fail_notMember() {
        // given
        long workspaceId = saveWorkspace("팀");
        long outsider = saveMember("이스타");

        // when & then
        assertThatThrownBy(
                () -> teammateRecordingService.findInProgress(
                        workspaceId,
                        outsider
                )
        ).isInstanceOf(WorkspaceException.class);
    }

    private long joinedMember(
            long workspaceId,
            String nickname
    ) {
        long memberId = saveMember(nickname);
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        return memberId;
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

    private Map<String, Object> storedRecording(long recordingId) {
        return jdbcClient.sql("""
                SELECT status, last_seen_at, accumulated_recording_millis, current_interval_started_at
                FROM recording_sessions
                WHERE id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query()
                .singleRow();
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

    private record StartedRecording(
            long id,
            RecordingControlCommand control
    ) {
    }
}
