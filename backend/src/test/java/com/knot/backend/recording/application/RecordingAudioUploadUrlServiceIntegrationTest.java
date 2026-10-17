package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingAudioUploadUrlServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingAudioUploadUrlService recordingAudioUploadUrlService;
    private final RecordingService recordingService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;

    RecordingAudioUploadUrlServiceIntegrationTest(
            RecordingAudioUploadUrlService recordingAudioUploadUrlService,
            RecordingService recordingService,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient
    ) {
        this.recordingAudioUploadUrlService = recordingAudioUploadUrlService;
        this.recordingService = recordingService;
        this.transactionTemplate = transactionTemplate;
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
    @DisplayName("종료된 녹음에 업로드를 예약하면 서버가 정한 key와 파일 정보를 RESERVED로 저장하고 서명 URL을 준다")
    void issue_success_savesReservation() {
        // given
        Fixture fixture = endedRecording();

        // when
        RecordingAudioUploadUrlResult result = issue(
                fixture,
                1024L
        );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.uploadUrl())
                .startsWith("http://localhost:9000/knot-test-audio/recordings/" + fixture.recordingId() + "/")
                .contains("X-Amz-Signature=");
        assertThat(uploadRow(fixture.recordingId())).isEqualTo(
                new UploadRow(
                        result.uploadId(),
                        "audio/webm",
                        1024L,
                        "RESERVED"
                )
        );
        assertThat(storageKey(fixture.recordingId())).startsWith("recordings/" + fixture.recordingId() + "/");
    }

    @Test
    @DisplayName("같은 녹음에 동시에 업로드 URL을 요청해도 예약은 하나로 수렴한다")
    void issue_success_concurrentRequestsConverge() throws Exception {
        // given
        Fixture fixture = endedRecording();

        // when
        RaceResult<RecordingAudioUploadUrlResult, RecordingAudioUploadUrlResult> result = race(
                () -> issue(
                        fixture,
                        1024L
                ),
                () -> issue(
                        fixture,
                        1024L
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
        assertThat(
                result.first()
                        .value()
                        .uploadId()
        ).isEqualTo(
                result.second()
                        .value()
                        .uploadId()
        );
        assertThat(
                result.first()
                        .value()
                        .created()
        ).isNotEqualTo(
                result.second()
                        .value()
                        .created()
        );
        assertThat(uploadCount(fixture.recordingId())).isEqualTo(1);
    }

    @Test
    @DisplayName("업로드가 이미 완료된 녹음은 다시 예약하지 않고 기존 기록을 유지한다")
    void issue_failure_alreadyCompleted() {
        // given
        Fixture fixture = endedRecording();
        issue(
                fixture,
                1024L
        );
        completeUpload(fixture.recordingId());

        // when
        Throwable thrown = catchThrowable(
                () -> issue(
                        fixture,
                        2048L
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_ALREADY_COMPLETED);
        assertThat(uploadRow(fixture.recordingId()).contentLength()).isEqualTo(1024L);
        assertThat(uploadRow(fixture.recordingId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("한 녹음에 두 번째 업로드 행은 DB 제약으로 저장할 수 없다")
    void insert_failure_secondUploadForSameRecording() {
        // given
        Fixture fixture = endedRecording();
        issue(
                fixture,
                1024L
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient.sql("""
                        INSERT INTO recording_audio_uploads (recording_id, storage_key, content_type,
                            content_length, status, reserved_at)
                        VALUES (:recordingId, 'recordings/duplicate', 'audio/webm', 10,
                            'RESERVED', now())
                        """)
                        .param(
                                "recordingId",
                                fixture.recordingId()
                        )
                        .update()
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("완료 시각 없이 COMPLETED로 바꿀 수 없다")
    void update_failure_completedWithoutCompletedAt() {
        // given
        Fixture fixture = endedRecording();
        issue(
                fixture,
                1024L
        );

        // when
        Throwable thrown = catchThrowable(
                () -> jdbcClient.sql("""
                        UPDATE recording_audio_uploads SET status = 'COMPLETED' WHERE recording_id = :recordingId
                        """)
                        .param(
                                "recordingId",
                                fixture.recordingId()
                        )
                        .update()
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("예약 후 같은 트랜잭션에서 예외가 나면 예약을 저장하지 않는다")
    void issue_failure_rollsBackWhenOuterTransactionFails() {
        // given
        Fixture fixture = endedRecording();

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            issue(
                    fixture,
                    1024L
            );
            throw new IllegalStateException("예약 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(uploadCount(fixture.recordingId())).isZero();
    }

    private RecordingAudioUploadUrlResult issue(
            Fixture fixture,
            long contentLength
    ) {
        return recordingAudioUploadUrlService.issue(
                fixture.workspaceId(),
                fixture.memberId(),
                fixture.recordingId(),
                new RecordingAudioUploadUrlCommand(
                        "audio/webm",
                        contentLength
                )
        );
    }

    private Fixture endedRecording() {
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("업로드 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        UUID tabId = UUID.randomUUID();
        long recordingId = startRecording(
                workspaceId,
                memberId,
                tabId
        );
        recordingService.end(
                workspaceId,
                memberId,
                recordingId,
                new RecordingControlCommand(
                        tabId,
                        CONTROL_TOKEN
                )
        );
        return new Fixture(
                workspaceId,
                memberId,
                recordingId
        );
    }

    private void completeUpload(long recordingId) {
        jdbcClient.sql("""
                UPDATE recording_audio_uploads
                SET status = 'COMPLETED', completed_at = reserved_at
                WHERE recording_id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    private UploadRow uploadRow(long recordingId) {
        return jdbcClient.sql("""
                SELECT id, content_type, content_length, status FROM recording_audio_uploads
                WHERE recording_id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> new UploadRow(
                                resultSet.getLong("id"),
                                resultSet.getString("content_type"),
                                resultSet.getLong("content_length"),
                                resultSet.getString("status")
                        )
                )
                .single();
    }

    private String storageKey(long recordingId) {
        return jdbcClient.sql("SELECT storage_key FROM recording_audio_uploads WHERE recording_id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private long uploadCount(long recordingId) {
        return jdbcClient.sql("SELECT count(*) FROM recording_audio_uploads WHERE recording_id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Long.class)
                .single();
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

    private long startRecording(
            long workspaceId,
            long memberId,
            UUID tabId
    ) {
        return recordingService.start(
                workspaceId,
                memberId,
                command(tabId)
        )
                .recordingId();
    }

    private RecordingStartCommand command(UUID tabId) {
        return new RecordingStartCommand(
                UUID.randomUUID(),
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

    private record Fixture(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
    }

    private record UploadRow(
            long id,
            String contentType,
            long contentLength,
            String status
    ) {
    }
}
