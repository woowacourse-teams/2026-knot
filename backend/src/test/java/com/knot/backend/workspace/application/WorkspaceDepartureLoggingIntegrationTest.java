package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.knot.backend.document.application.DocumentArchivalService;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceDepartureLoggingIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final WorkspaceLeaveService service;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    @MockitoSpyBean
    private DocumentArchivalService documentArchivalService;
    @MockitoSpyBean
    private RecordingSessionRepository recordingSessionRepository;

    WorkspaceDepartureLoggingIntegrationTest(
            WorkspaceLeaveService service,
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
        MDC.put(
                "requestId",
                "departure-request"
        );
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("탈퇴 커밋 후 해당 멤버의 활성 녹음만 원인과 요청에 연결한다")
    void leave_success_committedDiscardIsScoped() {
        // given
        DepartureFixture fixture = departureFixture();
        long discardedId = saveRecording(
                fixture.workspaceId(),
                fixture.leavingId(),
                "RECORDING"
        );
        long endedId = saveRecording(
                fixture.workspaceId(),
                fixture.leavingId(),
                "ENDED"
        );
        long othersId = saveRecording(
                fixture.workspaceId(),
                fixture.ownerId(),
                "PAUSED"
        );
        transactionTemplate.executeWithoutResult(transaction -> doAnswer(invocation -> {
            assertThat(status(discardedId)).isEqualTo("DISCARDED");
            return invocation.callRealMethod();
        }).when(documentArchivalService)
                .archiveAfterMemberDeparture(
                        anyLong(),
                        anyLong(),
                        any()
                )
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.leave(
                    fixture.leavingId(),
                    fixture.workspaceId()
            );

            // then
            ILoggingEvent discard = terminal(
                    logs,
                    "recording.discard"
            );
            assertThat(
                    field(
                            discard,
                            "recordingId"
                    )
            ).isEqualTo(discardedId);
            assertThat(
                    field(
                            discard,
                            "reason"
                    )
            ).isEqualTo("WORKSPACE_MEMBER_LEFT");
            assertThat(
                    field(
                            discard,
                            "outcome"
                    )
            ).isEqualTo("discarded");
            assertThat(
                    field(
                            discard,
                            "requestId"
                    )
            ).isEqualTo("departure-request");
            assertThat(
                    field(
                            discard,
                            "workspaceId"
                    )
            ).isEqualTo(fixture.workspaceId());
            assertThat(
                    terminal(
                            logs,
                            "workspace.leave"
                    ).getLevel()
            ).isEqualTo(Level.INFO);
            assertThat(status(endedId)).isEqualTo("ENDED");
            assertThat(status(othersId)).isEqualTo("PAUSED");
            assertStructured(logs);
        }
    }

    @Test
    @DisplayName("바깥 트랜잭션 롤백은 처리한 녹음의 폐기를 모두 롤백으로 남긴다")
    void leave_failure_outerRollbackHasNoFalseDiscard() {
        // given
        DepartureFixture fixture = departureFixture();
        long recordingId = saveRecording(
                fixture.workspaceId(),
                fixture.leavingId(),
                "RECORDING"
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
                service.leave(
                        fixture.leavingId(),
                        fixture.workspaceId()
                );
                assertThat(
                        operationTerminals(
                                logs,
                                "recording.discard"
                        )
                ).isEmpty();
                throw new IllegalStateException("비공개 롤백 원인");
            }));

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "recording.discard"
                            ),
                            "outcome"
                    )
            ).isEqualTo("rolled_back");
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "workspace.leave"
                            ),
                            "outcome"
                    )
            ).isEqualTo("failed");
            assertThat(status(recordingId)).isEqualTo("RECORDING");
            assertThat(
                    activeMembership(
                            fixture.workspaceId(),
                            fixture.leavingId()
                    )
            ).isTrue();
            assertStructured(logs);
        }
    }

    @Test
    @DisplayName("문서 보관 실패는 실패 단계와 녹음 폐기 롤백을 구분한다")
    void leave_failure_documentArchiveRollsBackPreparedDiscard() {
        // given
        DepartureFixture fixture = departureFixture();
        long recordingId = saveRecording(
                fixture.workspaceId(),
                fixture.leavingId(),
                "RECORDING"
        );
        IllegalStateException expected = new IllegalStateException("secret-document-and-provider-body");
        transactionTemplate.executeWithoutResult(
                transaction -> doThrow(expected).when(documentArchivalService)
                        .archiveAfterMemberDeparture(
                                anyLong(),
                                anyLong(),
                                any()
                        )
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.leave(
                            fixture.leavingId(),
                            fixture.workspaceId()
                    )
            );

            // then
            assertThat(failure).isSameAs(expected);
            ILoggingEvent root = terminal(
                    logs,
                    "workspace.leave"
            );
            assertThat(
                    field(
                            root,
                            "outcome"
                    )
            ).isEqualTo("failed");
            assertThat(
                    field(
                            root,
                            "failedStage"
                    )
            ).isEqualTo("archive_documents");
            assertThat(root.getLevel()).isEqualTo(Level.ERROR);
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "recording.discard"
                            ),
                            "outcome"
                    )
            ).isEqualTo("rolled_back");
            assertThat(status(recordingId)).isEqualTo("RECORDING");
            assertThat(
                    activeMembership(
                            fixture.workspaceId(),
                            fixture.leavingId()
                    )
            ).isTrue();
            assertThat(logs.events()).allSatisfy(
                    event -> assertThat(event.getFormattedMessage()).doesNotContain("secret-document-and-provider-body")
            );
            assertStructured(logs);
        }
    }

    @Test
    @DisplayName("두 번째 녹음 저장 실패는 첫 번째 녹음도 롤백하고 실패한 녹음을 식별한다")
    void leave_failure_secondRecordingSave() {
        // given
        long memberId = saveMember("last");
        long workspaceId = saveWorkspace("마지막 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        long firstId = saveRecording(
                workspaceId,
                memberId,
                "RECORDING"
        );
        long secondId = saveRecording(
                workspaceId,
                saveMember("former"),
                "PAUSED"
        );
        IllegalStateException expected = new IllegalStateException("secret-sql");
        doAnswer(invocation -> {
            RecordingSession recording = invocation.getArgument(0);
            if (recording.getId() == secondId) {
                throw expected;
            }
            return invocation.callRealMethod();
        }).when(recordingSessionRepository)
                .save(any(RecordingSession.class));
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.leave(
                            memberId,
                            workspaceId
                    )
            );

            // then
            assertThat(failure).rootCause()
                    .isSameAs(expected);
            List<ILoggingEvent> discards = operationTerminals(
                    logs,
                    "recording.discard"
            );
            assertThat(discards).hasSize(2);
            assertThat(discards).anySatisfy(event -> {
                assertThat(
                        field(
                                event,
                                "recordingId"
                        )
                ).isEqualTo(firstId);
                assertThat(
                        field(
                                event,
                                "outcome"
                        )
                ).isEqualTo("rolled_back");
            })
                    .anySatisfy(event -> {
                        assertThat(
                                field(
                                        event,
                                        "recordingId"
                                )
                        ).isEqualTo(secondId);
                        assertThat(
                                field(
                                        event,
                                        "outcome"
                                )
                        ).isEqualTo("failed");
                        assertThat(
                                field(
                                        event,
                                        "failedStage"
                                )
                        ).isEqualTo("save_recording");
                        assertThat(
                                field(
                                        event,
                                        "reason"
                                )
                        ).isEqualTo("WORKSPACE_DELETED");
                    });
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "workspace.leave"
                            ),
                            "failedStage"
                    )
            ).isEqualTo("discard_recordings");
            assertThat(status(firstId)).isEqualTo("RECORDING");
            assertThat(status(secondId)).isEqualTo("PAUSED");
            assertStructured(logs);
        }
    }

    @Test
    @DisplayName("폐기할 녹음이 없어도 마지막 멤버 탈퇴의 워크스페이스 삭제를 구분한다")
    void leave_success_lastMemberWithoutRecordings() {
        // given
        long memberId = saveMember("마지막소유자");
        long workspaceId = saveWorkspace("녹음없는팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.leave(
                    memberId,
                    workspaceId
            );

            // then
            assertThat(
                    jdbcClient.sql("SELECT deleted_at IS NOT NULL FROM workspaces WHERE id = :id")
                            .param(
                                    "id",
                                    workspaceId
                            )
                            .query(Boolean.class)
                            .single()
            ).isTrue();
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "workspace.leave"
                            ),
                            "outcome"
                    )
            ).isEqualTo("left_and_workspace_deleted");
            assertThat(
                    operationTerminals(
                            logs,
                            "recording.discard"
                    )
            ).isEmpty();
            assertStructured(logs);
        }
    }

    @Test
    @DisplayName("이미 탈퇴한 멤버의 재요청은 폐기 이벤트 없이 재실행 결과를 남긴다")
    void leave_success_replayed() {
        // given
        DepartureFixture fixture = departureFixture();
        service.leave(
                fixture.leavingId(),
                fixture.workspaceId()
        );
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.leave(
                    fixture.leavingId(),
                    fixture.workspaceId()
            );

            // then
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "workspace.leave"
                            ),
                            "outcome"
                    )
            ).isEqualTo("replayed");
            assertThat(logs.events()).noneSatisfy(
                    event -> assertThat(
                            field(
                                    event,
                                    "event"
                            )
                    ).isEqualTo("recording.discard")
            );
        }
    }

    @Test
    @DisplayName("소유자 탈퇴 거절은 기존 오류 코드를 유지하고 상태 변경 전 실패 단계를 남긴다")
    void leave_failure_expectedRejection() {
        // given
        DepartureFixture fixture = departureFixture();
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            Throwable failure = catchThrowable(
                    () -> service.leave(
                            fixture.ownerId(),
                            fixture.workspaceId()
                    )
            );

            // then
            assertThat(failure).isInstanceOf(WorkspaceException.class)
                    .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                    .isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED);
            ILoggingEvent root = terminal(
                    logs,
                    "workspace.leave"
            );
            assertThat(
                    field(
                            root,
                            "outcome"
                    )
            ).isEqualTo("rejected");
            assertThat(
                    field(
                            root,
                            "errorCode"
                    )
            ).isEqualTo("WORKSPACE_OWNER_TRANSFER_REQUIRED");
            assertThat(
                    field(
                            root,
                            "failedStage"
                    )
            ).isEqualTo("change_membership");
            assertThat(root.getLevel()).isEqualTo(Level.INFO);
            assertThat(
                    operationTerminals(
                            logs,
                            "recording.discard"
                    )
            ).isEmpty();
        }
    }

    @Test
    @DisplayName("소유권 승계 후보 조회는 조회 단계별 시간과 완료 결과를 남긴다")
    void candidates_success_stagesHaveElapsedTime() {
        // given
        DepartureFixture fixture = departureFixture();
        try (LogCapture logs = new LogCapture(WorkspaceLog.class)) {
            // when
            service.findOwnershipTransferCandidates(
                    fixture.workspaceId(),
                    fixture.ownerId()
            );

            // then
            assertThat(
                    field(
                            terminal(
                                    logs,
                                    "workspace.ownership_candidates"
                            ),
                            "outcome"
                    )
            ).isEqualTo("completed");
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(
                        field(
                                event,
                                "stage"
                        )
                ).isEqualTo("load_candidates");
                assertThat(
                        field(
                                event,
                                "outcome"
                        )
                ).isEqualTo("prepared");
                assertThat(
                        ((Number) field(
                                event,
                                "elapsedMs"
                        )).longValue()
                ).isGreaterThanOrEqualTo(0);
            });
            assertStructured(logs);
        }
    }

    private DepartureFixture departureFixture() {
        long ownerId = saveMember("비공개소유자");
        long leavingId = saveMember("비공개탈퇴자");
        long workspaceId = saveWorkspace("비공개팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                leavingId,
                "MEMBER"
        );
        return new DepartureFixture(
                workspaceId,
                ownerId,
                leavingId
        );
    }

    private ILoggingEvent terminal(
            LogCapture logs,
            String eventName
    ) {
        assertThat(
                operationTerminals(
                        logs,
                        eventName
                )
        ).hasSize(1);
        return operationTerminals(
                logs,
                eventName
        ).getFirst();
    }

    private List<ILoggingEvent> operationTerminals(
            LogCapture logs,
            String eventName
    ) {
        return logs.events()
                .stream()
                .filter(
                        event -> eventName.equals(
                                field(
                                        event,
                                        "event"
                                )
                        )
                )
                .filter(
                        event -> "operation".equals(
                                field(
                                        event,
                                        "stage"
                                )
                        )
                )
                .filter(
                        event -> !"started".equals(
                                field(
                                        event,
                                        "outcome"
                                )
                        )
                )
                .toList();
    }

    private Object field(
            ILoggingEvent event,
            String name
    ) {
        return event.getKeyValuePairs()
                .stream()
                .filter(pair -> name.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    private void assertStructured(LogCapture logs) {
        assertThat(logs.events()).allSatisfy(event -> {
            JsonNode json = objectMapper.readTree(event.getFormattedMessage());
            event.getKeyValuePairs()
                    .forEach(
                            pair -> assertThat(
                                    json.get(pair.key)
                                            .asText()
                            ).isEqualTo(pair.value.toString())
                    );
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "비공개소유자",
                    "비공개탈퇴자",
                    "비공개팀",
                    "secret-sql"
            );
        });
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

    private boolean activeMembership(
            long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql(
                "SELECT left_at IS NULL FROM workspace_members WHERE workspace_id=:workspaceId AND member_id=:memberId"
        )
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(Boolean.class)
                .single();
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

    private long saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role
    ) {
        return jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, :role, CAST(:joinedAt AS TIMESTAMPTZ))
                RETURNING id
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
                .query(Long.class)
                .single();
    }

    private long saveRecording(
            long workspaceId,
            long memberId,
            String status
    ) {
        return jdbcClient.sql("""
                INSERT INTO recording_sessions (
                    workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                    started_at, current_interval_started_at, ended_at, last_seen_at
                )
                VALUES (
                    :workspaceId, :memberId, gen_random_uuid(), gen_random_uuid(), repeat('a', 64), :status,
                    CAST(:startedAt AS TIMESTAMPTZ),
                    CASE WHEN :status = 'RECORDING' THEN CAST(:startedAt AS TIMESTAMPTZ) END,
                    CASE WHEN :status = 'ENDED' THEN CAST(:startedAt AS TIMESTAMPTZ) END,
                    CAST(:startedAt AS TIMESTAMPTZ)
                )
                RETURNING id
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
                        "status",
                        status
                )
                .param(
                        "startedAt",
                        JOINED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private record DepartureFixture(
            long workspaceId,
            long ownerId,
            long leavingId
    ) {
    }
}
