package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
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
class RecordingStartServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String OTHER_CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefE";

    private final RecordingService recordingService;
    private final JdbcClient jdbcClient;

    RecordingStartServiceIntegrationTest(
            RecordingService recordingService,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_members, workspaces,
                    oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("같은 요청 키를 재시도하면 기존 세션을 반환한다")
    void start_success_replaySameRequest() {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        RecordingStartCommand command = command(
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN
        );
        RecordingStartResult firstResult = recordingService.start(
                workspaceId,
                memberId,
                command
        );

        // when
        RecordingStartResult secondResult = recordingService.start(
                workspaceId,
                memberId,
                command
        );

        // then
        assertThat(secondResult.created()).isFalse();
        assertThat(secondResult.recordingId()).isEqualTo(firstResult.recordingId());
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 멤버가 동시에 시작해도 활성 세션은 하나만 생성된다")
    void start_success_concurrentActiveRecordingAcrossWorkspaces() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long firstWorkspaceId = saveWorkspace("첫 팀");
        long secondWorkspaceId = saveWorkspace("두 번째 팀");
        saveWorkspaceMember(
                firstWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                secondWorkspaceId,
                memberId
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<StartAttempt> startFirst = startAfterBarrier(
                barrier,
                firstWorkspaceId,
                memberId,
                command(
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );
        Callable<StartAttempt> startSecond = startAfterBarrier(
                barrier,
                secondWorkspaceId,
                memberId,
                command(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        OTHER_CONTROL_TOKEN
                )
        );

        // when
        List<StartAttempt> attempts = raceStarts(
                startFirst,
                startSecond
        );

        // then
        assertThat(attempts).filteredOn(StartAttempt::created)
                .hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.created())
                .singleElement()
                .extracting(StartAttempt::errorCode)
                .isEqualTo(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS.getCode());
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 시작 요청이 동시에 도착하면 생성 하나와 재시도 하나가 같은 ID로 수렴한다")
    void start_success_concurrentSameRequest() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<StartAttempt> request = startAfterBarrier(
                barrier,
                workspaceId,
                memberId,
                command(
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );

        // when
        List<StartAttempt> attempts = raceStarts(
                request,
                request
        );

        // then
        assertThat(attempts).allMatch(attempt -> attempt.errorCode() == null);
        assertThat(attempts).filteredOn(StartAttempt::created)
                .hasSize(1);
        assertThat(attempts).extracting(StartAttempt::recordingId)
                .containsOnly(
                        attempts.getFirst()
                                .recordingId()
                );
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 요청 키 입력이 달라지면 기존 세션만 남긴다")
    void start_failure_sameRequestDifferentInputRollsBack() {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        recordingService.start(
                workspaceId,
                memberId,
                command(
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );
        RecordingStartCommand changedCommand = command(
                REQUEST_ID,
                TAB_ID,
                OTHER_CONTROL_TOKEN
        );

        // when
        assertThatThrownBy(
                () -> recordingService.start(
                        workspaceId,
                        memberId,
                        changedCommand
                )
        ).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_START_REQUEST_CONFLICT);

        // then
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 세션을 저장하지 않고 롤백한다")
    void start_failure_workspaceAccessDeniedRollsBack() {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");

        // when
        assertThatThrownBy(
                () -> recordingService.start(
                        workspaceId,
                        memberId,
                        command(
                                REQUEST_ID,
                                TAB_ID,
                                CONTROL_TOKEN
                        )
                )
        );

        // then
        assertThat(countRecordingSessions()).isZero();
    }

    private Callable<StartAttempt> startAfterBarrier(
            CyclicBarrier barrier,
            long workspaceId,
            long memberId,
            RecordingStartCommand command
    ) {
        return () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            try {
                RecordingStartResult result = recordingService.start(
                        workspaceId,
                        memberId,
                        command
                );
                return StartAttempt.accepted(result);
            } catch (RecordingException exception) {
                return StartAttempt.rejected(
                        exception.getErrorCode()
                                .getCode()
                );
            }
        };
    }

    private List<StartAttempt> raceStarts(
            Callable<StartAttempt> first,
            Callable<StartAttempt> second
    ) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<StartAttempt> firstResult = executorService.submit(first);
            Future<StartAttempt> secondResult = executorService.submit(second);
            return List.of(
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

    private RecordingStartCommand command(
            UUID requestId,
            UUID tabId,
            String controlToken
    ) {
        return new RecordingStartCommand(
                requestId,
                tabId,
                controlToken
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
                VALUES (:workspaceId, :memberId, 'MEMBER', CAST(:joinedAt AS TIMESTAMPTZ))
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

    private int countRecordingSessions() {
        return jdbcClient.sql("SELECT COUNT(*) FROM recording_sessions")
                .query(Integer.class)
                .single();
    }

    private record StartAttempt(
            boolean created,
            Long recordingId,
            String errorCode
    ) {

        private static StartAttempt accepted(RecordingStartResult result) {
            return new StartAttempt(
                    result.created(),
                    result.recordingId(),
                    null
            );
        }

        private static StartAttempt rejected(String errorCode) {
            return new StartAttempt(
                    false,
                    null,
                    errorCode
            );
        }
    }
}
