package com.knot.backend.recording.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

// 주기 작업이 켜진 컨텍스트가 캐시에 남으면 같은 DB를 쓰는 다른 테스트의 녹음을 회수하므로 끝나면 닫는다
@DirtiesContext
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingConnectionExpirySchedulerIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 100L;

    private final RecordingService recordingService;
    private final JdbcClient jdbcClient;

    RecordingConnectionExpirySchedulerIntegrationTest(
            RecordingService recordingService,
            JdbcClient jdbcClient
    ) {
        this.recordingService = recordingService;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void enableScheduler(DynamicPropertyRegistry registry) {
        registry.add(
                "recording.connection-expiry.enabled",
                () -> "true"
        );
        registry.add(
                "recording.connection-expiry.interval",
                () -> "PT0.1S"
        );
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
    @DisplayName("회수 작업을 켜면 요청 없이 남은 녹음을 주기 작업이 연결 만료로 종료한다")
    void scheduler_success_reclaimsWithoutRequest() throws InterruptedException {
        // given
        long recordingId = startRecording();

        // when
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - INTERVAL '10 minutes',
                    current_interval_started_at = current_interval_started_at - INTERVAL '10 minutes',
                    last_seen_at = last_seen_at - INTERVAL '10 minutes'
                WHERE id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .update();

        // then
        assertThat(waitForStatus(recordingId)).isEqualTo("ENDED");
        assertThat(
                jdbcClient.sql("SELECT end_reason FROM recording_sessions WHERE id = :recordingId")
                        .param(
                                "recordingId",
                                recordingId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("CONNECTION_EXPIRED");
    }

    private String waitForStatus(long recordingId) throws InterruptedException {
        Instant deadline = Instant.now()
                .plus(WAIT_LIMIT);
        String status = status(recordingId);
        while (!"ENDED".equals(status) && Instant.now()
                .isBefore(deadline)) {
            Thread.sleep(POLL_MILLIS);
            status = status(recordingId);
        }
        return status;
    }

    private String status(long recordingId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private long startRecording() {
        long memberId = jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES ('member', NULL)
                RETURNING id
                """)
                .query(Long.class)
                .single();
        long workspaceId = jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES ('주기 팀', CAST(:createdAt AS TIMESTAMPTZ))
                RETURNING id
                """)
                .param(
                        "createdAt",
                        CREATED_AT.toString()
                )
                .query(Long.class)
                .single();
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
                        CREATED_AT.toString()
                )
                .update();
        return recordingService.start(
                workspaceId,
                memberId,
                new RecordingStartCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA"
                )
        )
                .recordingId();
    }
}
