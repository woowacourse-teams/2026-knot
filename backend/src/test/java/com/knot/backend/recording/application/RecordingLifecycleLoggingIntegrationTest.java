package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingLifecycleLoggingIntegrationTest {
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final RecordingStartService startService;
    private final RecordingControlService controlService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;
    private long memberId;
    private long workspaceId;

    RecordingLifecycleLoggingIntegrationTest(
            RecordingStartService startService,
            RecordingControlService controlService,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient
    ) {
        this.startService = startService;
        this.controlService = controlService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_invitations, workspace_members, workspaces,
                    oauth_identities, members RESTART IDENTITY CASCADE
                """)
                .update();
        memberId = jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url) VALUES ('로그 검증 회원', NULL) RETURNING id
                """)
                .query(Long.class)
                .single();
        workspaceId = jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at) VALUES ('로그 검증', NOW()) RETURNING id
                """)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'OWNER', NOW())
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .update();
    }

    @Test
    @DisplayName("시작 요청의 재전송은 새 녹음 생성 대신 기존 녹음 ID의 replayed로 기록한다")
    void start_replayed() {
        // given
        RecordingStartCommand command = startCommand();
        long recordingId = startService.start(
                workspaceId,
                memberId,
                command
        )
                .recordingId();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            startService.start(
                    workspaceId,
                    memberId,
                    command
            );

            // then
            assertThat(logs.events()).filteredOn(
                    event -> has(
                            event,
                            "outcome",
                            "replayed"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                has(
                                        event,
                                        "event",
                                        "recording.start"
                                )
                        ).isTrue();
                        assertThat(
                                has(
                                        event,
                                        "recordingId",
                                        recordingId
                                )
                        ).isTrue();
                    });
            assertThat(logs.events()).noneMatch(
                    event -> has(
                            event,
                            "outcome",
                            "created"
                    )
            );
        }
    }

    @Test
    @DisplayName("일시정지 요청이 만료로 거절되어도 noRollbackFor로 확정된 자동 종료를 기록한다")
    void pause_rejectedButExpiryCommitted() {
        // given
        RecordingStartCommand command = startCommand();
        long recordingId = startService.start(
                workspaceId,
                memberId,
                command
        )
                .recordingId();
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = :oldTime, current_interval_started_at = :oldTime, last_seen_at = :oldTime
                WHERE id = :id
                """)
                .param(
                        "oldTime",
                        Instant.now()
                                .minusSeconds(60)
                                .atOffset(java.time.ZoneOffset.UTC)
                )
                .param(
                        "id",
                        recordingId
                )
                .update();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(
                    () -> controlService.pause(
                            workspaceId,
                            memberId,
                            recordingId,
                            new RecordingControlCommand(
                                    command.tabId(),
                                    CONTROL_TOKEN
                            )
                    )
            );

            // then
            assertThat(thrown).isInstanceOf(RecordingException.class);
            assertThat(status(recordingId)).isEqualTo("ENDED");
            assertThat(logs.events()).filteredOn(
                    event -> has(
                            event,
                            "event",
                            "recording.auto_end"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(
                                has(
                                        event,
                                        "outcome",
                                        "changed"
                                )
                        ).isTrue();
                        assertThat(
                                has(
                                        event,
                                        "endReason",
                                        "CONNECTION_EXPIRED"
                                )
                        ).isTrue();
                    });
            assertThat(logs.events()).filteredOn(
                    event -> has(
                            event,
                            "outcome",
                            "rejected"
                    )
            )
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(
                                has(
                                        event,
                                        "errorCode",
                                        "RECORDING_ALREADY_ENDED"
                                )
                        ).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("상위 트랜잭션 롤백 시 일시정지 성공을 기록하지 않는다")
    void pause_outerRollback() {
        // given
        RecordingStartCommand command = startCommand();
        long recordingId = startService.start(
                workspaceId,
                memberId,
                command
        )
                .recordingId();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
                controlService.pause(
                        workspaceId,
                        memberId,
                        recordingId,
                        new RecordingControlCommand(
                                command.tabId(),
                                CONTROL_TOKEN
                        )
                );
                throw new IllegalStateException("롤백 검증");
            }));

            // then
            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(status(recordingId)).isEqualTo("RECORDING");
            assertThat(logs.events()).noneMatch(
                    event -> has(
                            event,
                            "outcome",
                            "changed"
                    )
            );
            assertThat(logs.events()).anyMatch(
                    event -> has(
                            event,
                            "outcome",
                            "failed"
                    )
            );
        }
    }

    @Test
    @DisplayName("정상 heartbeat에는 업무 로그를 추가하지 않는다")
    void heartbeat_quiet() {
        // given
        RecordingStartCommand command = startCommand();
        long recordingId = startService.start(
                workspaceId,
                memberId,
                command
        )
                .recordingId();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            controlService.heartbeat(
                    workspaceId,
                    memberId,
                    recordingId,
                    new RecordingControlCommand(
                            command.tabId(),
                            CONTROL_TOKEN
                    )
            );

            // then
            assertThat(logs.events()).isEmpty();
        }
    }

    private RecordingStartCommand startCommand() {
        return new RecordingStartCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CONTROL_TOKEN
        );
    }

    private String status(long recordingId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private boolean has(
            ILoggingEvent event,
            String key,
            Object value
    ) {
        return event.getKeyValuePairs() != null && event.getKeyValuePairs()
                .stream()
                .anyMatch(
                        pair -> pair.key.equals(key) && String.valueOf(pair.value)
                                .equals(String.valueOf(value))
                );
    }
}
