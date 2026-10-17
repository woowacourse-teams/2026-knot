package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingDetailResult;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.application.WorkspaceLeaveService;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
class RecordingDetailServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final int READ_COUNT = 200;

    private final RecordingDetailService recordingDetailService;
    private final RecordingService recordingService;
    private final WorkspaceLeaveService workspaceLeaveService;
    private final JdbcClient jdbcClient;

    RecordingDetailServiceIntegrationTest(
            RecordingDetailService recordingDetailService,
            RecordingService recordingService,
            WorkspaceLeaveService workspaceLeaveService,
            JdbcClient jdbcClient
    ) {
        this.recordingDetailService = recordingDetailService;
        this.recordingService = recordingService;
        this.workspaceLeaveService = workspaceLeaveService;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_audio_uploads, recording_sessions, workspace_invitations, workspace_members,
                    workspaces, oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("진행 중·일시정지·종료 녹음과 업로드 예약·완료를 구분해 조회한다")
    void find_success_distinguishesStatesAndUploads() {
        // given
        Fixture recordingFixture = ownerFixture("진행 팀");
        StartedRecording recording = startRecording(recordingFixture);
        Fixture pausedFixture = ownerFixture("일시정지 팀");
        StartedRecording paused = startRecording(pausedFixture);
        recordingService.pause(
                pausedFixture.workspaceId(),
                pausedFixture.memberId(),
                paused.id(),
                paused.control()
        );
        Fixture reservedFixture = ownerFixture("예약 팀");
        StartedRecording reserved = endedRecording(reservedFixture);
        reserveUpload(reserved.id());
        Fixture completedFixture = ownerFixture("완료 팀");
        StartedRecording completed = endedRecording(completedFixture);
        completeUpload(reserveUpload(completed.id()));

        // when
        List<RecordingDetailResult> results = List.of(
                find(
                        recordingFixture,
                        recording
                ),
                find(
                        pausedFixture,
                        paused
                ),
                find(
                        reservedFixture,
                        reserved
                ),
                find(
                        completedFixture,
                        completed
                )
        );

        // then
        assertThat(results).extracting(RecordingDetailResult::status)
                .containsExactly(
                        RecordingStatus.RECORDING,
                        RecordingStatus.PAUSED,
                        RecordingStatus.ENDED,
                        RecordingStatus.ENDED
                );
        assertThat(results).extracting(RecordingDetailResult::audioUploadStatus)
                .containsExactly(
                        null,
                        null,
                        RecordingAudioUploadStatus.RESERVED,
                        RecordingAudioUploadStatus.COMPLETED
                );
        assertThat(
                results.get(2)
                        .endReason()
        ).isEqualTo(RecordingEndReason.USER_ENDED);
    }

    @Test
    @DisplayName("상세 조회를 반복해도 상태·마지막 신호·제어 증명이 바뀌지 않는다")
    void find_success_doesNotChangeSession() {
        // given
        Fixture fixture = ownerFixture("조회 팀");
        StartedRecording recording = startRecording(fixture);
        String before = sessionRow(recording.id());

        // when
        for (int i = 0; i < 3; i++) {
            find(
                    fixture,
                    recording
            );
        }

        // then
        assertThat(sessionRow(recording.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("생존 신호가 연결 만료를 확정한 뒤 상세 조회는 같은 종료 시각과 사유를 반환한다")
    void find_success_matchesHeartbeatExpiry() {
        // given
        Fixture fixture = ownerFixture("만료 팀");
        StartedRecording recording = startRecording(fixture);
        disconnect(recording.id());
        RecordingHeartbeatResult heartbeat = recordingService.heartbeat(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id(),
                recording.control()
        );

        // when
        RecordingDetailResult result = find(
                fixture,
                recording
        );

        // then
        assertThat(result.status()).isEqualTo(RecordingStatus.ENDED);
        assertThat(result.endedAt()).isEqualTo(heartbeat.endedAt());
        assertThat(result.endReason()).isEqualTo(RecordingEndReason.CONNECTION_EXPIRED);
    }

    @Test
    @DisplayName("같은 Workspace의 다른 회원 녹음은 시작자가 아니라는 이유로 403이다")
    void find_failure_otherMember() {
        // given
        AccessFixture fixture = accessFixture();

        // when
        Object errorCode = errorCode(
                fixture.workspaceId(),
                fixture.ownerId(),
                fixture.memberRecordingId()
        );

        // then
        assertThat(errorCode).isEqualTo(RecordingErrorCode.RECORDING_CONTROL_DENIED);
    }

    @Test
    @DisplayName("다른 Workspace 경로로 본인 녹음을 조회하면 녹음 없음 404다")
    void find_failure_otherWorkspace() {
        // given
        AccessFixture fixture = accessFixture();

        // when
        Object errorCode = errorCode(
                fixture.otherWorkspaceId(),
                fixture.ownerId(),
                fixture.ownerRecordingId()
        );

        // then
        assertThat(errorCode).isEqualTo(RecordingErrorCode.RECORDING_NOT_FOUND);
    }

    @Test
    @DisplayName("탈퇴로 녹음이 폐기된 회원은 Workspace 접근 403으로 막혀 폐기 상태를 볼 수 없다")
    void find_failure_leftMember() {
        // given
        AccessFixture fixture = accessFixture();
        workspaceLeaveService.leave(
                fixture.memberId(),
                fixture.workspaceId()
        );

        // when
        Object errorCode = errorCode(
                fixture.workspaceId(),
                fixture.memberId(),
                fixture.memberRecordingId()
        );

        // then
        assertThat(errorCode).isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    private AccessFixture accessFixture() {
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("권한 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
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
        saveWorkspaceMember(
                otherWorkspaceId,
                ownerId,
                "OWNER"
        );
        StartedRecording ownerRecording = startRecording(
                new Fixture(
                        workspaceId,
                        ownerId
                )
        );
        StartedRecording memberRecording = startRecording(
                new Fixture(
                        workspaceId,
                        memberId
                )
        );
        return new AccessFixture(
                workspaceId,
                otherWorkspaceId,
                ownerId,
                memberId,
                ownerRecording.id(),
                memberRecording.id()
        );
    }

    @Test
    @DisplayName("종료와 업로드 완료가 조회와 겹쳐도 진행 중 상태와 업로드 정보가 섞인 조합을 반환하지 않는다")
    void find_success_neverMixesActiveWithUpload() throws Exception {
        // given
        Fixture fixture = ownerFixture("경합 팀");
        StartedRecording recording = startRecording(fixture);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        // when
        List<RecordingDetailResult> results;
        try {
            Future<List<RecordingDetailResult>> reads = executorService.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                List<RecordingDetailResult> collected = new ArrayList<>();
                for (int i = 0; i < READ_COUNT; i++) {
                    collected.add(
                            find(
                                    fixture,
                                    recording
                            )
                    );
                }
                return collected;
            });
            Future<?> writes = executorService.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                recordingService.end(
                        fixture.workspaceId(),
                        fixture.memberId(),
                        recording.id(),
                        recording.control()
                );
                completeUpload(reserveUpload(recording.id()));
                return null;
            });
            writes.get(
                    10,
                    TimeUnit.SECONDS
            );
            results = reads.get(
                    10,
                    TimeUnit.SECONDS
            );
        } finally {
            executorService.shutdownNow();
        }

        // then
        assertThat(results).hasSize(READ_COUNT)
                .allSatisfy(
                        result -> assertThat(
                                RecordingStatus.ACTIVE_STATUSES.contains(result.status())
                                        && result.audioUploadStatus() != null
                        ).isFalse()
                );
    }

    private Object errorCode(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        Throwable thrown = catchThrowable(
                () -> recordingDetailService.find(
                        workspaceId,
                        memberId,
                        recordingId
                )
        );
        assertThat(thrown).isInstanceOf(ProjectException.class);
        return ((ProjectException) thrown).getErrorCode();
    }

    private RecordingDetailResult find(
            Fixture fixture,
            StartedRecording recording
    ) {
        return recordingDetailService.find(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id()
        );
    }

    private StartedRecording endedRecording(Fixture fixture) {
        StartedRecording recording = startRecording(fixture);
        recordingService.end(
                fixture.workspaceId(),
                fixture.memberId(),
                recording.id(),
                recording.control()
        );
        return recording;
    }

    private long reserveUpload(long recordingId) {
        return jdbcClient.sql("""
                INSERT INTO recording_audio_uploads (
                    recording_id, storage_key, content_type, content_length, status, reserved_at
                )
                VALUES (:recordingId, gen_random_uuid()::text, 'audio/webm', 1024, 'RESERVED', now())
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Long.class)
                .single();
    }

    private void completeUpload(long uploadId) {
        jdbcClient.sql("""
                UPDATE recording_audio_uploads
                SET status = 'COMPLETED', completed_at = reserved_at
                WHERE id = :uploadId
                """)
                .param(
                        "uploadId",
                        uploadId
                )
                .update();
    }

    // 실제 30초를 기다리지 않도록 녹음의 모든 시각을 같은 만큼 과거로 옮겨 신호가 끊긴 상태를 만든다
    private void disconnect(long recordingId) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - INTERVAL '5 minutes',
                    current_interval_started_at = current_interval_started_at - INTERVAL '5 minutes',
                    last_seen_at = last_seen_at - INTERVAL '5 minutes'
                WHERE id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    private String sessionRow(long recordingId) {
        return jdbcClient.sql("SELECT row_to_json(rs)::text FROM recording_sessions rs WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
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

    private record Fixture(
            long workspaceId,
            long memberId
    ) {
    }

    private record AccessFixture(
            long workspaceId,
            long otherWorkspaceId,
            long ownerId,
            long memberId,
            long ownerRecordingId,
            long memberRecordingId
    ) {
    }

    private record StartedRecording(
            long id,
            RecordingControlCommand control
    ) {
    }
}
