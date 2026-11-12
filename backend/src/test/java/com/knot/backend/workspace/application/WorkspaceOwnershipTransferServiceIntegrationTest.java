package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceOwnershipTransferServiceIntegrationTest {
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final WorkspaceLeaveService leaveService;
    private final WorkspaceService lastViewedService;
    private final WorkspaceInvitationService invitationService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final JdbcClient jdbcClient;

    WorkspaceOwnershipTransferServiceIntegrationTest(
            WorkspaceLeaveService leaveService,
            WorkspaceService lastViewedService,
            WorkspaceInvitationService invitationService,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            TransactionTemplate transactionTemplate,
            EntityManager entityManager,
            JdbcClient jdbcClient
    ) {
        this.leaveService = leaveService;
        this.lastViewedService = lastViewedService;
        this.invitationService = invitationService;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE workspace_invitations, workspace_members, workspaces, oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @DisplayName("승계는 기존 참여 이력을 보존하며 이전 OWNER의 마지막 조회만 해제한다")
    @Test
    void transferOwnership_success_preservesHistoryAndOtherData() {
        // given
        Fixture fixture = fixture();
        long otherId = saveMember("other");
        long otherMembershipId = saveMembership(
                fixture.workspaceId(),
                otherId,
                "MEMBER"
        );
        long otherWorkspaceId = saveWorkspace();
        saveMembership(
                otherWorkspaceId,
                fixture.ownerId(),
                "OWNER"
        );
        long oldTargetMembershipId = saveMembership(
                fixture.workspaceId(),
                otherId,
                "MEMBER",
                JOINED_AT.minusSeconds(10),
                JOINED_AT.minusSeconds(1)
        );
        invitationService.issue(
                fixture.workspaceId(),
                fixture.ownerId()
        );
        markLastViewed(fixture.ownerMembershipId());
        markLastViewed(fixture.targetMembershipId());
        List<String> workspacesBefore = snapshot("workspaces");
        List<String> invitationsBefore = snapshot("workspace_invitations");
        List<String> membersBefore = snapshot("members");
        MembershipState otherBefore = membership(otherMembershipId);
        MembershipState historyBefore = membership(oldTargetMembershipId);
        MembershipState ownerBefore = membership(fixture.ownerMembershipId());
        MembershipState targetBefore = membership(fixture.targetMembershipId());

        // when
        leaveService.transferOwnershipAndLeave(
                fixture.ownerId(),
                fixture.workspaceId(),
                fixture.targetId()
        );

        // then
        MembershipState owner = membership(fixture.ownerMembershipId());
        MembershipState target = membership(fixture.targetMembershipId());
        assertThat(owner.role()).isEqualTo("OWNER");
        assertThat(owner.leftAt()).isNotNull();
        assertThat(owner.joinedAt()).isEqualTo(ownerBefore.joinedAt());
        assertThat(owner.joinedAt()).isNotEqualTo(target.joinedAt());
        assertThat(owner.lastViewed()).isFalse();
        assertThat(target.role()).isEqualTo("OWNER");
        assertThat(target.leftAt()).isNull();
        assertThat(target.joinedAt()).isEqualTo(targetBefore.joinedAt());
        assertThat(target.lastViewed()).isTrue();
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        assertThat(snapshot("workspaces")).isEqualTo(workspacesBefore);
        assertThat(snapshot("workspace_invitations")).isEqualTo(invitationsBefore);
        assertThat(snapshot("members")).isEqualTo(membersBefore);
        assertThat(membership(otherMembershipId)).isEqualTo(otherBefore);
        assertThat(membership(oldTargetMembershipId)).isEqualTo(historyBefore);
        assertThat(snapshot("workspace_members")).hasSize(5);
    }

    @DisplayName("승계 후 탈퇴하면 이전 OWNER의 진행 중 녹음만 폐기하고 승계자의 녹음은 보존한다")
    @Test
    void transferOwnership_success_discardsOwnerActiveRecording() {
        // given
        Fixture fixture = fixture();
        long ownerRecordingId = saveRecording(
                fixture.workspaceId(),
                fixture.ownerId(),
                "RECORDING"
        );
        long targetRecordingId = saveRecording(
                fixture.workspaceId(),
                fixture.targetId(),
                "PAUSED"
        );

        // when
        transfer(fixture);

        // then
        assertThat(recordingStatus(ownerRecordingId)).isEqualTo("DISCARDED");
        assertThat(recordingStatus(targetRecordingId)).isEqualTo("PAUSED");
    }

    @DisplayName("필요한 활성 참여 행만 membership ID 오름차순으로 잠금 조회한다")
    @Test
    void lockParticipants_success_ordersMembershipRowsAndExcludesHistory() {
        // given
        Fixture fixture = fixture();
        saveMembership(
                fixture.workspaceId(),
                fixture.targetId(),
                "MEMBER",
                JOINED_AT.minusSeconds(10),
                JOINED_AT.minusSeconds(1)
        );
        long otherId = saveMember("other");
        saveMembership(
                fixture.workspaceId(),
                otherId,
                "MEMBER"
        );
        long otherWorkspaceId = saveWorkspace();
        saveMembership(
                otherWorkspaceId,
                fixture.targetId(),
                "MEMBER"
        );

        // when
        List<WorkspaceMember> locked = transactionTemplate.execute(status -> {
            workspaceRepository.findByIdForUpdate(fixture.workspaceId());
            return workspaceMemberRepository.findAllActiveByWorkspaceIdAndMemberIdsForUpdate(
                    fixture.workspaceId(),
                    List.of(
                            fixture.ownerId(),
                            fixture.targetId()
                    )
            );
        });

        // then
        assertThat(locked).extracting(WorkspaceMember::getId)
                .containsExactly(
                        fixture.targetMembershipId(),
                        fixture.ownerMembershipId()
                );
        assertThat(locked).allMatch(WorkspaceMember::isActive);
        assertThat(locked).extracting(WorkspaceMember::getWorkspaceId)
                .containsOnly(fixture.workspaceId());
    }

    @DisplayName("두 참여 변경을 flush한 뒤 실패하면 역할과 탈퇴·마지막 조회를 모두 롤백한다")
    @Test
    void transferOwnership_failure_rollsBackFlushedChanges() {
        // given
        Fixture fixture = fixture();
        markLastViewed(fixture.ownerMembershipId());
        List<String> before = snapshot("workspace_members");

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            leaveService.transferOwnershipAndLeave(
                    fixture.ownerId(),
                    fixture.workspaceId(),
                    fixture.targetId()
            );
            entityManager.flush();
            throw new IllegalStateException("승계 저장 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("승계 저장 후 롤백 검증");
        assertThat(snapshot("workspace_members")).isEqualTo(before);
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
    }

    @DisplayName("이전 OWNER 탈퇴 저장의 실제 DB 실패는 대상 승계도 롤백한다")
    @Test
    void transferOwnership_failure_rollsBackOnDatabaseWriteFailure() {
        // given
        Fixture fixture = fixture();
        markLastViewed(fixture.ownerMembershipId());
        List<String> before = snapshot("workspace_members");
        jdbcClient.sql("""
                ALTER TABLE workspace_members ADD CONSTRAINT test_ownership_transfer_failure
                CHECK (member_id <> %d OR left_at IS NULL)
                """.formatted(fixture.ownerId()))
                .update();

        try {
            // when
            Throwable thrown = catchThrowable(
                    () -> leaveService.transferOwnershipAndLeave(
                            fixture.ownerId(),
                            fixture.workspaceId(),
                            fixture.targetId()
                    )
            );

            // then
            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(snapshot("workspace_members")).isEqualTo(before);
            assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        } finally {
            jdbcClient.sql("ALTER TABLE workspace_members DROP CONSTRAINT test_ownership_transfer_failure")
                    .update();
        }
    }

    @DisplayName("승계 후 같은 요청자의 재요청은 403이며 두 번째 대상에게 승계하지 않는다")
    @Test
    void transferOwnership_failure_retryDoesNotTransferAgain() {
        // given
        Fixture fixture = fixture();
        long otherId = saveMember("other");
        saveMembership(
                fixture.workspaceId(),
                otherId,
                "MEMBER"
        );
        transfer(fixture);
        List<String> before = snapshot("workspace_members");

        // when
        Throwable thrown = catchThrowable(
                () -> leaveService.transferOwnershipAndLeave(
                        fixture.ownerId(),
                        fixture.workspaceId(),
                        otherId
                )
        );

        // then
        assertError(
                thrown,
                WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED
        );
        assertThat(snapshot("workspace_members")).isEqualTo(before);
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
    }

    @DisplayName("탈퇴한 생성자가 기존 초대로 재가입해도 MEMBER로 돌아온다")
    @Test
    void accept_success_previousOwnerRejoinsAsMember() {
        // given
        Fixture fixture = fixture();
        WorkspaceInvitationResult invitation = invitationService.issue(
                fixture.workspaceId(),
                fixture.ownerId()
        );
        transfer(fixture);
        MembershipState oldParticipation = membership(fixture.ownerMembershipId());

        // when
        invitationService.accept(
                invitation.code(),
                "previous-owner-rejoin",
                fixture.ownerId()
        );

        // then
        assertThat(membership(fixture.ownerMembershipId())).isEqualTo(oldParticipation);
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        assertThat(
                jdbcClient.sql("""
                        SELECT role FROM workspace_members
                        WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
                        """)
                        .param(
                                "workspaceId",
                                fixture.workspaceId()
                        )
                        .param(
                                "memberId",
                                fixture.ownerId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("MEMBER");
    }

    @DisplayName("서로 다른 대상을 지목한 동시 승계는 하나만 성공한다")
    @Test
    void transferOwnership_race_oneTransferWins() throws Exception {
        // given
        Fixture fixture = fixture();
        long otherId = saveMember("other");
        saveMembership(
                fixture.workspaceId(),
                otherId,
                "MEMBER"
        );

        // when
        RaceResult result = race(
                () -> transfer(fixture),
                () -> leaveService.transferOwnershipAndLeave(
                        fixture.ownerId(),
                        fixture.workspaceId(),
                        otherId
                )
        );

        // then
        assertThat(result.errors()).containsExactlyInAnyOrder(
                null,
                WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED
        );
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        assertThat(membership(fixture.ownerMembershipId()).leftAt()).isNotNull();
        assertThat(activeMemberCount(fixture.workspaceId())).isEqualTo(2);
    }

    @DisplayName("대상 탈퇴와 승계는 탈퇴 후 승계 거절 또는 승계 후 OWNER 탈퇴 거절로 끝난다")
    @Test
    void transferOwnership_race_targetLeave() throws Exception {
        // given
        Fixture fixture = fixture();
        saveMembership(
                fixture.workspaceId(),
                saveMember("other"),
                "MEMBER"
        );

        // when
        RaceResult result = race(
                () -> transfer(fixture),
                () -> leaveService.leave(
                        fixture.targetId(),
                        fixture.workspaceId()
                )
        );

        // then
        if (result.first() == null) {
            assertThat(result.second()).isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED);
            assertThat(membership(fixture.targetMembershipId()).role()).isEqualTo("OWNER");
            assertThat(membership(fixture.targetMembershipId()).leftAt()).isNull();
            assertThat(membership(fixture.ownerMembershipId()).leftAt()).isNotNull();
        } else {
            assertThat(result.first()).isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT);
            assertThat(result.second()).isNull();
            assertThat(membership(fixture.ownerMembershipId()).leftAt()).isNull();
            assertThat(membership(fixture.targetMembershipId()).leftAt()).isNotNull();
        }
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
    }

    @DisplayName("Workspace 잠금 뒤 권한을 확인하는 논리 삭제와 승계는 하나만 성공한다")
    @Test
    void transferOwnership_race_workspaceDeletion() throws Exception {
        // given
        Fixture fixture = fixture();

        // when
        RaceResult result = race(
                () -> transfer(fixture),
                () -> deleteWorkspaceWithOwnerCheck(fixture)
        );

        // then
        if (result.first() == null) {
            assertThat(result.second()).isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED);
            assertThat(deletedWorkspaceCount(fixture.workspaceId())).isZero();
            assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        } else {
            assertThat(result.first()).isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
            assertThat(result.second()).isNull();
            assertThat(deletedWorkspaceCount(fixture.workspaceId())).isEqualTo(1);
            assertThat(workspaceRepository.findById(fixture.workspaceId())).isEmpty();
        }
    }

    @DisplayName("초대 수락과 승계가 경합해도 기존 초대와 새 MEMBER를 보존한다")
    @Test
    void transferOwnership_race_invitationAcceptance() throws Exception {
        // given
        Fixture fixture = fixture();
        long joiningId = saveMember("joining");
        WorkspaceInvitationResult invitation = invitationService.issue(
                fixture.workspaceId(),
                fixture.ownerId()
        );
        List<String> invitationBefore = snapshot("workspace_invitations");

        // when
        RaceResult result = race(
                () -> transfer(fixture),
                () -> invitationService.accept(
                        invitation.code(),
                        "accept-transfer-race",
                        joiningId
                )
        );

        // then
        assertThat(result.first()).isNull();
        assertThat(result.second()).isNull();
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
        assertThat(activeMemberCount(fixture.workspaceId())).isEqualTo(2);
        assertThat(snapshot("workspace_invitations")).isEqualTo(invitationBefore);
        assertThat(
                jdbcClient.sql("""
                        SELECT role FROM workspace_members
                        WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
                        """)
                        .param(
                                "workspaceId",
                                fixture.workspaceId()
                        )
                        .param(
                                "memberId",
                                joiningId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("MEMBER");
    }

    @DisplayName("승계와 요청자 또는 대상의 마지막 조회 갱신이 경합해도 탈퇴 행이 다시 표시되지 않는다")
    @ParameterizedTest
    @ValueSource(strings = {"owner", "target"})
    void transferOwnership_race_lastViewed(String actor) throws Exception {
        // given
        Fixture fixture = fixture();
        long viewerId = actor.equals("owner") ? fixture.ownerId() : fixture.targetId();
        markLastViewed(fixture.ownerMembershipId());

        // when
        RaceResult result = race(
                () -> transfer(fixture),
                () -> lastViewedService.updateLastViewed(
                        viewerId,
                        fixture.workspaceId()
                )
        );

        // then
        assertThat(result.first()).isNull();
        if (actor.equals("owner")) {
            assertThat(result.second()).isIn(
                    null,
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND
            );
        } else {
            assertThat(result.second()).isNull();
            assertThat(membership(fixture.targetMembershipId()).lastViewed()).isTrue();
        }
        assertThat(membership(fixture.ownerMembershipId()).lastViewed()).isFalse();
        assertThat(membership(fixture.ownerMembershipId()).leftAt()).isNotNull();
        assertThat(activeOwnerCount(fixture.workspaceId())).isEqualTo(1);
    }

    private Fixture fixture() {
        long ownerId = saveMember("owner");
        long targetId = saveMember("target");
        long workspaceId = saveWorkspace();
        long targetMembershipId = saveMembership(
                workspaceId,
                targetId,
                "MEMBER"
        );
        long ownerMembershipId = saveMembership(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT.plusSeconds(60),
                null
        );
        return new Fixture(
                ownerId,
                targetId,
                workspaceId,
                ownerMembershipId,
                targetMembershipId
        );
    }

    private void transfer(Fixture fixture) {
        leaveService.transferOwnershipAndLeave(
                fixture.ownerId(),
                fixture.workspaceId(),
                fixture.targetId()
        );
    }

    private long saveMember(String nickname) {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES (:nickname, NULL) RETURNING id
                """)
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    private long saveWorkspace() {
        return jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES ('승계 팀', CAST(:joinedAt AS TIMESTAMPTZ)) RETURNING id
                """)
                .param(
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private long saveMembership(
            long workspaceId,
            long memberId,
            String role
    ) {
        return saveMembership(
                workspaceId,
                memberId,
                role,
                JOINED_AT,
                null
        );
    }

    private long saveMembership(
            long workspaceId,
            long memberId,
            String role,
            Instant joinedAt,
            Instant leftAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, left_at)
                VALUES (:workspaceId, :memberId, :role, CAST(:joinedAt AS TIMESTAMPTZ), CAST(:leftAt AS TIMESTAMPTZ))
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
                        joinedAt.toString()
                )
                .param(
                        "leftAt",
                        leftAt == null ? null : leftAt.toString()
                )
                .query(Long.class)
                .single();
    }

    private void markLastViewed(long membershipId) {
        jdbcClient.sql("UPDATE workspace_members SET last_viewed = TRUE WHERE id = :id")
                .param(
                        "id",
                        membershipId
                )
                .update();
    }

    private MembershipState membership(long membershipId) {
        return jdbcClient.sql("""
                SELECT role, joined_at::text AS joined_at, left_at::text AS left_at, last_viewed
                FROM workspace_members WHERE id = :id
                """)
                .param(
                        "id",
                        membershipId
                )
                .query(MembershipState.class)
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
                    started_at, current_interval_started_at, last_seen_at
                )
                VALUES (
                    :workspaceId, :memberId, gen_random_uuid(), gen_random_uuid(), repeat('a', 64), :status,
                    CAST(:startedAt AS TIMESTAMPTZ),
                    CASE WHEN :status = 'RECORDING' THEN CAST(:startedAt AS TIMESTAMPTZ) END,
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

    private String recordingStatus(long recordingId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private List<String> snapshot(String table) {
        return jdbcClient.sql("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")
                .query(String.class)
                .list();
    }

    private long activeOwnerCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM workspace_members
                WHERE workspace_id = :id AND role = 'OWNER' AND left_at IS NULL
                """)
                .param(
                        "id",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long activeMemberCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM workspace_members WHERE workspace_id = :id AND left_at IS NULL
                """)
                .param(
                        "id",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long deletedWorkspaceCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM workspaces WHERE id = :id AND deleted_at IS NOT NULL
                """)
                .param(
                        "id",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private RaceResult race(
            Runnable first,
            Runnable second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceErrorCode> firstResult = executor.submit(
                    afterBarrier(
                            barrier,
                            first
                    )
            );
            Future<WorkspaceErrorCode> secondResult = executor.submit(
                    afterBarrier(
                            barrier,
                            second
                    )
            );
            return new RaceResult(
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
            executor.shutdownNow();
        }
    }

    private Callable<WorkspaceErrorCode> afterBarrier(
            CyclicBarrier barrier,
            Runnable operation
    ) {
        return () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            try {
                operation.run();
                return null;
            } catch (WorkspaceException exception) {
                return (WorkspaceErrorCode) exception.getErrorCode();
            }
        };
    }

    private void deleteWorkspaceWithOwnerCheck(Fixture fixture) {
        transactionTemplate.executeWithoutResult(status -> {
            Workspace workspace = workspaceRepository.findByIdForUpdate(fixture.workspaceId())
                    .orElseThrow(() -> new WorkspaceException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
            if (!workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                    fixture.workspaceId(),
                    fixture.ownerId(),
                    WorkspaceMemberRole.OWNER
            )) {
                throw new WorkspaceException(WorkspaceErrorCode.WORKSPACE_OWNER_REQUIRED);
            }
            workspace.delete(Instant.now());
            workspaceRepository.save(workspace);
        });
    }

    private void assertError(
            Throwable thrown,
            WorkspaceErrorCode errorCode
    ) {
        assertThat(thrown).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }

    private record Fixture(
            long ownerId,
            long targetId,
            long workspaceId,
            long ownerMembershipId,
            long targetMembershipId
    ) {
    }

    private record MembershipState(
            String role,
            String joinedAt,
            String leftAt,
            boolean lastViewed
    ) {
    }

    private record RaceResult(
            WorkspaceErrorCode first,
            WorkspaceErrorCode second
    ) {
        private List<WorkspaceErrorCode> errors() {
            return java.util.Arrays.asList(
                    first,
                    second
            );
        }
    }
}
