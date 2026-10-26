package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.application.dto.result.RecordingPauseResult;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
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
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.RECORDING);
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
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PAUSED);
            assertThat(current.elapsedMillis()).isEqualTo(paused.elapsedMillis());
        });
        assertThat(storedRecording(recording.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("종료 후 문서 생성 중인 녹음을 반복 조회해도 녹음과 배치 저장값은 바뀌지 않는다")
    void findCurrent_success_processingReadDoesNotChangeState() {
        // given
        long memberId = saveMember("processing-member");
        long workspaceId = saveWorkspace("처리 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
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
        saveBatch(
                recording.id(),
                DocumentGenerationProcessingStatus.RUNNING
        );
        Map<String, Object> beforeRecording = storedRow(
                "recording_sessions",
                recording.id()
        );
        Map<String, Object> beforeBatch = storedBatch(recording.id());
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
            assertThat(current.recordingId()).isEqualTo(recording.id());
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING);
        });
        assertThat(
                storedRow(
                        "recording_sessions",
                        recording.id()
                )
        ).isEqualTo(beforeRecording);
        assertThat(storedBatch(recording.id())).isEqualTo(beforeBatch);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationProcessingStatus.class, names = {"SUCCEEDED", "FAILED", "NO_CONTENT"})
    @DisplayName("최신 녹음이 최종 상태라도 이전 생성 중 녹음을 다시 선택한다")
    void findCurrent_success_latestTerminalRestoresPreviousProcessing(DocumentGenerationProcessingStatus status) {
        // given
        long memberId = saveMember("terminal-member");
        long workspaceId = saveWorkspace("최신 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording previous = startRecording(
                workspaceId,
                memberId
        );
        recordingService.end(
                workspaceId,
                memberId,
                previous.id(),
                previous.control()
        );
        saveBatch(
                previous.id(),
                DocumentGenerationProcessingStatus.RUNNING
        );
        StartedRecording latest = startRecording(
                workspaceId,
                memberId
        );
        recordingService.end(
                workspaceId,
                memberId,
                latest.id(),
                latest.control()
        );
        saveBatch(
                latest.id(),
                status
        );

        // when
        Optional<RecordingCurrentResult> result = recordingService.findCurrent(
                workspaceId,
                memberId
        );

        // then
        assertThat(result).hasValueSatisfying(current -> {
            assertThat(current.recordingId()).isEqualTo(previous.id());
            assertThat(current.status()).isEqualTo(CurrentRecordingStatus.PROCESSING);
        });
        assertThat(storedBatch(previous.id()).get("processing_status")).isEqualTo("RUNNING");
    }

    @Test
    void findCurrent_success_activeThenProcessingThenPreviousFailure() {
        long memberId = saveMember("restore-member");
        long workspaceId = saveWorkspace("복원 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording failed = startRecording(
                workspaceId,
                memberId
        );
        recordingService.end(
                workspaceId,
                memberId,
                failed.id(),
                failed.control()
        );
        saveTranscriptionFailure(failed.id());
        StartedRecording latest = startRecording(
                workspaceId,
                memberId
        );
        assertThat(
                recordingService.findCurrent(
                        workspaceId,
                        memberId
                )
                        .orElseThrow()
                        .recordingId()
        ).isEqualTo(latest.id());
        recordingService.end(
                workspaceId,
                memberId,
                latest.id(),
                latest.control()
        );
        saveBatch(
                latest.id(),
                DocumentGenerationProcessingStatus.RUNNING
        );
        assertThat(
                recordingService.findCurrent(
                        workspaceId,
                        memberId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(CurrentRecordingStatus.PROCESSING);
        jdbcClient.sql(
                "UPDATE document_generation_batches SET processing_status='SUCCEEDED', finished_at=CURRENT_TIMESTAMP WHERE recording_session_id=:id"
        )
                .param(
                        "id",
                        latest.id()
                )
                .update();
        var result = recordingService.findCurrent(
                workspaceId,
                memberId
        )
                .orElseThrow();
        assertThat(result.recordingId()).isEqualTo(failed.id());
        assertThat(result.status()).isEqualTo(CurrentRecordingStatus.TRANSCRIPTION_FAILED);
        assertThat(result.retryable()).isFalse();
        assertThat(result.retryableJobIds()).isEmpty();
    }

    @Test
    void findCurrent_success_documentFailureRetryableRegardlessOfUserRetriesUntilDisplayPeriod() {
        long memberId = saveMember("document-member");
        long workspaceId = saveWorkspace("실패 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
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
        saveBatch(
                recording.id(),
                DocumentGenerationProcessingStatus.FAILED
        );
        long jobId = saveFailedJob(recording.id());
        Map<String, Object> before = storedRow(
                "document_generation_jobs",
                jobId
        );
        var result = recordingService.findCurrent(
                workspaceId,
                memberId
        )
                .orElseThrow();
        assertThat(result.status()).isEqualTo(CurrentRecordingStatus.DOCUMENT_FAILED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.retryableJobIds()).containsExactly(jobId);
        assertThat(
                storedRow(
                        "document_generation_jobs",
                        jobId
                )
        ).isEqualTo(before);
        jdbcClient.sql("UPDATE document_generation_jobs SET user_retry_count=3, attempt_count=4 WHERE id=:id")
                .param(
                        "id",
                        jobId
                )
                .update();
        result = recordingService.findCurrent(
                workspaceId,
                memberId
        )
                .orElseThrow();
        assertThat(result.status()).isEqualTo(CurrentRecordingStatus.DOCUMENT_FAILED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.retryableJobIds()).containsExactly(jobId);
        jdbcClient.sql(
                "UPDATE document_generation_jobs SET last_failed_at=CURRENT_TIMESTAMP - INTERVAL '169 hours', expires_at=CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id=:id"
        )
                .param(
                        "id",
                        jobId
                )
                .update();
        assertThat(
                recordingService.findCurrent(
                        workspaceId,
                        memberId
                )
        ).isEmpty();
    }

    @Test
    void findCurrent_success_latestFailureTieAndTenantIsolation() {
        long memberId = saveMember("owner");
        long otherId = saveMember("other");
        long workspaceId = saveWorkspace("첫 팀");
        long otherWorkspace = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        saveWorkspaceMember(
                workspaceId,
                otherId
        );
        saveWorkspaceMember(
                otherWorkspace,
                memberId
        );
        long expected = 0;
        for (int index = 0; index < 4; index++) {
            long targetWorkspace = index == 3 ? otherWorkspace : workspaceId;
            long targetMember = index == 2 ? otherId : memberId;
            StartedRecording recording = startRecording(
                    targetWorkspace,
                    targetMember
            );
            recordingService.end(
                    targetWorkspace,
                    targetMember,
                    recording.id(),
                    recording.control()
            );
            saveTranscriptionFailure(recording.id());
            jdbcClient.sql("UPDATE recording_sessions SET started_at=CAST(:at AS TIMESTAMPTZ) WHERE id=:id")
                    .param(
                            "at",
                            CREATED_AT.toString()
                    )
                    .param(
                            "id",
                            recording.id()
                    )
                    .update();
            if (index == 1)
                expected = recording.id();
        }
        assertThat(
                recordingService.findCurrent(
                        workspaceId,
                        memberId
                )
                        .orElseThrow()
                        .recordingId()
        ).isEqualTo(expected);
    }

    private void saveTranscriptionFailure(long recordingId) {
        jdbcClient.sql(
                "INSERT INTO recording_transcriptions (recording_session_id, attempt_id, status, failure_code) VALUES (:id, :attempt, 'FAILED', 'TEST_FAILURE')"
        )
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "attempt",
                        UUID.randomUUID()
                )
                .update();
    }

    private long saveFailedJob(long recordingId) {
        return jdbcClient
                .sql(
                        """
                                INSERT INTO document_generation_jobs
                                    (transcript_id, batch_id, stage, status, created_at, updated_at, last_failed_at, expires_at, failure_cause)
                                SELECT transcript_id, id, 'CLASSIFICATION', 'FAILED', accepted_at, CURRENT_TIMESTAMP,
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '168 hours', 'INTERNAL'
                                FROM document_generation_batches WHERE recording_session_id=:id RETURNING id
                                """
                )
                .param(
                        "id",
                        recordingId
                )
                .query(Long.class)
                .single();
    }

    private Map<String, Object> storedRow(
            String table,
            long id
    ) {
        return jdbcClient.sql("SELECT * FROM " + table + " WHERE id = :id")
                .param(
                        "id",
                        id
                )
                .query()
                .singleRow();
    }

    private Map<String, Object> storedBatch(long recordingId) {
        return jdbcClient.sql("SELECT * FROM document_generation_batches WHERE recording_session_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query()
                .singleRow();
    }

    private void saveBatch(
            long recordingId,
            DocumentGenerationProcessingStatus status
    ) {
        long transcriptId = jdbcClient.sql("""
                INSERT INTO transcripts (recording_session_id, content, created_at)
                VALUES (:recordingId, '회의 내용', CURRENT_TIMESTAMP)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Long.class)
                .single();
        String registrationState = "TOPICS_REGISTERED";
        if (status == DocumentGenerationProcessingStatus.NO_CONTENT) {
            registrationState = "NO_CONTENT";
        }
        java.sql.Timestamp finishedAt = null;
        if (status == DocumentGenerationProcessingStatus.SUCCEEDED
                || status == DocumentGenerationProcessingStatus.FAILED
                || status == DocumentGenerationProcessingStatus.NO_CONTENT) {
            finishedAt = java.sql.Timestamp.from(CREATED_AT);
        }
        java.sql.Timestamp cleanupRequestedAt = null;
        if (status == DocumentGenerationProcessingStatus.NO_CONTENT) {
            cleanupRequestedAt = java.sql.Timestamp.from(CREATED_AT);
        }
        jdbcClient.sql("""
                INSERT INTO document_generation_batches
                    (recording_session_id, transcript_id, topic_registration_state,
                        accepted_at, registered_at, cleanup_requested_at, processing_status, finished_at)
                VALUES (:recordingId, :transcriptId, :registrationState,
                    :acceptedAt, :registeredAt, :cleanupRequestedAt, :status, :finishedAt)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .param(
                        "registrationState",
                        registrationState
                )
                .param(
                        "acceptedAt",
                        java.sql.Timestamp.from(CREATED_AT)
                )
                .param(
                        "registeredAt",
                        java.sql.Timestamp.from(CREATED_AT)
                )
                .param(
                        "cleanupRequestedAt",
                        cleanupRequestedAt,
                        java.sql.Types.TIMESTAMP
                )
                .param(
                        "status",
                        status.name()
                )
                .param(
                        "finishedAt",
                        finishedAt,
                        java.sql.Types.TIMESTAMP
                )
                .update();
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
