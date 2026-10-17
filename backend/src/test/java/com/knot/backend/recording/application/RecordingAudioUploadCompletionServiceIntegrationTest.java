package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.recording.application.dto.command.RecordingAudioUploadUrlCommand;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingAudioUploadCompletionServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingAudioUploadUrlService recordingAudioUploadUrlService;
    private final RecordingAudioUploadCompletionService recordingAudioUploadCompletionService;
    private final RecordingService recordingService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;
    private final Clock clock;
    @MockitoBean
    private RecordingAudioStorage recordingAudioStorage;

    RecordingAudioUploadCompletionServiceIntegrationTest(
            RecordingAudioUploadUrlService recordingAudioUploadUrlService,
            RecordingAudioUploadCompletionService recordingAudioUploadCompletionService,
            RecordingService recordingService,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient,
            Clock clock
    ) {
        this.recordingAudioUploadUrlService = recordingAudioUploadUrlService;
        this.recordingAudioUploadCompletionService = recordingAudioUploadCompletionService;
        this.recordingService = recordingService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
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

    @BeforeEach
    void stubPresign() {
        when(
                recordingAudioStorage.presignUpload(
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenReturn(
                new PresignedAudioUpload(
                        "https://storage.example/upload",
                        clock.instant()
                                .plusSeconds(3600)
                )
        );
    }

    @Test
    @DisplayName("저장소의 파일이 예약과 같으면 업로드 완료와 완료 시각을 기록한다")
    void complete_success_matchingObject() {
        // given
        Fixture fixture = endedRecording();
        long uploadId = issue(
                fixture,
                1024L
        ).uploadId();
        stubStoredObject(
                1024L,
                "audio/webm"
        );

        // when
        RecordingAudioUploadCompletionResult result = complete(
                fixture,
                uploadId
        );

        // then
        assertThat(result.uploadStatus()).isEqualTo(RecordingAudioUploadStatus.COMPLETED);
        assertThat(uploadRow(fixture.recordingId()).status()).isEqualTo("COMPLETED");
        assertThat(completedAt(fixture.recordingId())).isEqualTo(result.completedAt());
    }

    @Test
    @DisplayName("저장소에 파일이 없으면 409이고 예약 상태를 유지한다")
    void complete_failure_missingObject() {
        // given
        Fixture fixture = endedRecording();
        long uploadId = issue(
                fixture,
                1024L
        ).uploadId();
        when(recordingAudioStorage.findStoredObject(anyString())).thenReturn(StoredAudioObject.missing());

        // when
        Throwable thrown = catchThrowable(
                () -> complete(
                        fixture,
                        uploadId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_COMPLETED);
        assertThat(uploadRow(fixture.recordingId()).status()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("저장소 장애면 재시도 가능한 오류로 알리고 완료를 기록하지 않는다")
    void complete_failure_storageUnavailable() {
        // given
        Fixture fixture = endedRecording();
        long uploadId = issue(
                fixture,
                1024L
        ).uploadId();
        when(recordingAudioStorage.findStoredObject(anyString()))
                .thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        Throwable thrown = catchThrowable(
                () -> complete(
                        fixture,
                        uploadId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        assertThat(uploadRow(fixture.recordingId()).status()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("같은 업로드를 동시에 완료해도 하나의 완료 시각으로 수렴한다")
    void complete_success_concurrentCompletionsConverge() throws Exception {
        // given
        Fixture fixture = endedRecording();
        long uploadId = issue(
                fixture,
                1024L
        ).uploadId();
        stubStoredObject(
                1024L,
                "audio/webm"
        );

        // when
        RaceResult<RecordingAudioUploadCompletionResult, RecordingAudioUploadCompletionResult> result = race(
                () -> complete(
                        fixture,
                        uploadId
                ),
                () -> complete(
                        fixture,
                        uploadId
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
                        .completedAt()
        ).isEqualTo(
                result.second()
                        .value()
                        .completedAt()
        );
    }

    @Test
    @DisplayName("다른 녹음의 uploadId로 완료하면 404이고 두 예약을 바꾸지 않는다")
    void complete_failure_uploadOfOtherRecording() {
        // given
        Fixture fixture = endedRecording();
        Fixture other = endedRecording();
        long otherUploadId = issue(
                other,
                1024L
        ).uploadId();
        stubStoredObject(
                1024L,
                "audio/webm"
        );

        // when
        Throwable thrown = catchThrowable(
                () -> complete(
                        fixture,
                        otherUploadId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_NOT_FOUND);
        assertThat(uploadRow(other.recordingId()).status()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("완료된 녹음은 업로드 URL을 다시 발급하지 않는다")
    void issue_failure_afterCompletion() {
        // given
        Fixture fixture = endedRecording();
        long uploadId = issue(
                fixture,
                1024L
        ).uploadId();
        stubStoredObject(
                1024L,
                "audio/webm"
        );
        complete(
                fixture,
                uploadId
        );

        // when
        Throwable thrown = catchThrowable(
                () -> issue(
                        fixture,
                        1024L
                )
        );

        // then
        assertThat(thrown).isInstanceOf(ProjectException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.AUDIO_UPLOAD_ALREADY_COMPLETED);
    }

    private RecordingAudioUploadCompletionResult complete(
            Fixture fixture,
            long uploadId
    ) {
        return recordingAudioUploadCompletionService.complete(
                fixture.workspaceId(),
                fixture.memberId(),
                fixture.recordingId(),
                uploadId
        );
    }

    private void stubStoredObject(
            long contentLength,
            String contentType
    ) {
        when(recordingAudioStorage.findStoredObject(anyString())).thenReturn(
                StoredAudioObject.of(
                        contentLength,
                        contentType,
                        "version-1"
                )
        );
    }

    private Instant completedAt(long recordingId) {
        return jdbcClient.sql("SELECT completed_at FROM recording_audio_uploads WHERE recording_id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> resultSet.getObject(
                                "completed_at",
                                OffsetDateTime.class
                        )
                                .toInstant()
                )
                .single();
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
