package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationAcceptanceResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceLeaveServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final WorkspaceLeaveService workspaceLeaveService;
    private final WorkspaceService workspaceLastViewedService;
    private final WorkspaceInvitationService workspaceInvitationService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbcClient;

    WorkspaceLeaveServiceIntegrationTest(
            WorkspaceLeaveService workspaceLeaveService,
            WorkspaceService workspaceLastViewedService,
            WorkspaceInvitationService workspaceInvitationService,
            TransactionTemplate transactionTemplate,
            JdbcClient jdbcClient
    ) {
        this.workspaceLeaveService = workspaceLeaveService;
        this.workspaceLastViewedService = workspaceLastViewedService;
        this.workspaceInvitationService = workspaceInvitationService;
        this.transactionTemplate = transactionTemplate;
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

    @DisplayName("활성 MEMBER가 탈퇴하면 이력을 남기고 마지막 조회 상태를 해제한다")
    @Test
    void leave_success_activeMember() {
        // given
        long ownerMemberId = saveMember("owner");
        long leavingMemberId = saveMember("leaving");
        long workspaceId = saveWorkspace("탈퇴 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        long leavingMembershipId = saveWorkspaceMember(
                workspaceId,
                leavingMemberId,
                "MEMBER"
        );
        markLastViewed(leavingMembershipId);

        // when
        workspaceLeaveService.leave(
                leavingMemberId,
                workspaceId
        );

        // then
        MembershipState membership = membershipState(leavingMembershipId);
        assertThat(membership.leftAt()).isNotNull()
                .isAfterOrEqualTo(JOINED_AT);
        assertThat(membership.lastViewed()).isFalse();
        assertThat(workspaceDeletedAt(workspaceId)).isNull();
        assertThat(activeMembershipCount(workspaceId)).isEqualTo(1);
    }

    @DisplayName("이미 탈퇴한 사용자가 다시 탈퇴하면 기존 탈퇴 이력을 변경하지 않는다")
    @Test
    void leave_success_alreadyLeftMembership() {
        // given
        long memberId = saveMember("retry");
        long workspaceId = saveWorkspace("재시도 팀");
        long membershipId = saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        Instant originalLeftAt = JOINED_AT.plusSeconds(10);
        leaveMembership(
                membershipId,
                originalLeftAt
        );

        // when
        workspaceLeaveService.leave(
                memberId,
                workspaceId
        );

        // then
        assertThat(membershipState(membershipId).leftAt()).isEqualTo(originalLeftAt);
        assertThat(workspaceDeletedAt(workspaceId)).isNull();
    }

    @DisplayName("다른 활성 멤버가 있는 OWNER 탈퇴는 거절하고 상태를 롤백한다")
    @Test
    void leave_failure_ownerTransferRequiredRollsBack() {
        // given
        long ownerMemberId = saveMember("owner");
        long otherMemberId = saveMember("member");
        long workspaceId = saveWorkspace("OWNER 거절 팀");
        long ownerMembershipId = saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                otherMemberId,
                "MEMBER"
        );

        // when
        Throwable thrown = catchThrowable(
                () -> workspaceLeaveService.leave(
                        ownerMemberId,
                        workspaceId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED);
        assertThat(membershipState(ownerMembershipId).leftAt()).isNull();
        assertThat(workspaceDeletedAt(workspaceId)).isNull();
        assertThat(activeMembershipCount(workspaceId)).isEqualTo(2);
    }

    @DisplayName("마지막 활성 멤버가 탈퇴하면 멤버십과 워크스페이스를 같은 시각으로 논리 삭제한다")
    @Test
    void leave_success_lastActiveMemberDeletesWorkspace() {
        // given
        long memberId = saveMember("last");
        long workspaceId = saveWorkspace("마지막 팀");
        long membershipId = saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );

        // when
        workspaceLeaveService.leave(
                memberId,
                workspaceId
        );

        // then
        MembershipState membership = membershipState(membershipId);
        assertThat(membership.leftAt()).isNotNull();
        assertThat(workspaceDeletedAt(workspaceId)).isEqualTo(membership.leftAt());
        assertThat(activeMembershipCount(workspaceId)).isZero();
    }

    @DisplayName("삭제된 워크스페이스 탈퇴는 404로 거절하고 멤버십 이력을 변경하지 않는다")
    @Test
    void leave_failure_deletedWorkspaceNotFound() {
        // given
        long memberId = saveMember("deleted");
        long workspaceId = saveWorkspace("삭제된 팀");
        long membershipId = saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        deleteWorkspace(
                workspaceId,
                CREATED_AT.plusSeconds(60)
        );

        // when
        Throwable thrown = catchThrowable(
                () -> workspaceLeaveService.leave(
                        memberId,
                        workspaceId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
        assertThat(membershipState(membershipId).leftAt()).isNull();
    }

    @DisplayName("탈퇴와 마지막 조회 갱신이 경합해도 탈퇴한 멤버십은 마지막 조회로 남지 않는다")
    @Test
    void leave_success_concurrentLastViewedUpdateLeavesInactiveMembershipUnviewed() throws Exception {
        // given
        long ownerMemberId = saveMember("owner");
        long leavingMemberId = saveMember("racing");
        long workspaceId = saveWorkspace("경합 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        long leavingMembershipId = saveWorkspaceMember(
                workspaceId,
                leavingMemberId,
                "MEMBER"
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<WorkspaceErrorCode> leave = outcomeAfterBarrier(
                barrier,
                () -> workspaceLeaveService.leave(
                        leavingMemberId,
                        workspaceId
                )
        );
        Callable<WorkspaceErrorCode> updateLastViewed = outcomeAfterBarrier(
                barrier,
                () -> workspaceLastViewedService.updateLastViewed(
                        leavingMemberId,
                        workspaceId
                )
        );

        try {
            // when
            RaceResult result = awaitRaceResult(
                    executorService,
                    leave,
                    updateLastViewed
            );

            // then
            assertThat(result.leaveErrorCode()).isNull();
            assertThat(result.lastViewedErrorCode()).isIn(
                    null,
                    WorkspaceErrorCode.WORKSPACE_NOT_FOUND
            );
            MembershipState membership = membershipState(leavingMembershipId);
            assertThat(membership.leftAt()).isNotNull();
            assertThat(membership.lastViewed()).isFalse();
            assertThat(lastViewedCount(leavingMemberId)).isZero();
        } finally {
            executorService.shutdownNow();
        }
    }

    @DisplayName("탈퇴한 사용자가 실제 초대를 수락하면 기존 이력을 보존하고 새 MEMBER 멤버십을 만든다")
    @Test
    void leave_success_rejoinWithInvitationKeepsHistory() {
        // given
        long ownerMemberId = saveMember("owner");
        long leavingMemberId = saveMember("rejoin");
        long workspaceId = saveWorkspace("초대 재가입 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        long leftMembershipId = saveWorkspaceMember(
                workspaceId,
                leavingMemberId,
                "MEMBER"
        );
        WorkspaceInvitationResult invitation = workspaceInvitationService.issue(
                workspaceId,
                ownerMemberId
        );
        workspaceLeaveService.leave(
                leavingMemberId,
                workspaceId
        );
        MembershipState leftMembership = membershipState(leftMembershipId);

        // when
        WorkspaceInvitationAcceptanceResult result = workspaceInvitationService.accept(
                invitation.code(),
                "rejoin-remote",
                leavingMemberId
        );

        // then
        MembershipState activeMembership = latestMembershipState(
                workspaceId,
                leavingMemberId
        );
        assertThat(result.membershipCreated()).isTrue();
        assertThat(leftMembership.id()).isEqualTo(leftMembershipId);
        assertThat(leftMembership.role()).isEqualTo("MEMBER");
        assertThat(leftMembership.joinedAt()).isEqualTo(JOINED_AT);
        assertThat(leftMembership.leftAt()).isNotNull();
        assertThat(activeMembership.id()).isNotEqualTo(leftMembershipId);
        assertThat(activeMembership.role()).isEqualTo("MEMBER");
        assertThat(activeMembership.leftAt()).isNull();
        assertThat(
                workspaceMembershipCount(
                        workspaceId,
                        leavingMemberId
                )
        ).isEqualTo(2);
        assertThat(activeMembershipCount(workspaceId)).isEqualTo(2);
    }

    @DisplayName("마지막 OWNER 탈퇴와 초대 수락 경합은 수락 후 탈퇴 거절 또는 삭제 후 초대 404로만 끝난다")
    @Test
    void leave_successOrConflict_whenRacingWithInvitationAccept() throws Exception {
        // given
        long ownerMemberId = saveMember("owner");
        long joiningMemberId = saveMember("joining");
        long workspaceId = saveWorkspace("탈퇴 수락 경합 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        WorkspaceInvitationResult invitation = workspaceInvitationService.issue(
                workspaceId,
                ownerMemberId
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<WorkspaceErrorCode> leave = outcomeAfterBarrier(
                barrier,
                () -> workspaceLeaveService.leave(
                        ownerMemberId,
                        workspaceId
                )
        );
        Callable<AcceptOutcome> accept = acceptOutcomeAfterBarrier(
                barrier,
                invitation.code(),
                joiningMemberId
        );

        try {
            // when
            LeaveAcceptRaceResult result = awaitLeaveAcceptRaceResult(
                    executorService,
                    leave,
                    accept
            );

            // then
            boolean acceptedThenLeaveRejected = result.acceptOutcome()
                    .accepted() && result.leaveErrorCode() == WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED;
            boolean deletedThenAcceptRejected = !result.acceptOutcome()
                    .accepted()
                    && result.acceptOutcome()
                            .errorCode() == WorkspaceErrorCode.WORKSPACE_INVITATION_PREVIEW_NOT_FOUND
                    && result.leaveErrorCode() == null;
            assertThat(acceptedThenLeaveRejected || deletedThenAcceptRejected).isTrue();
            if (workspaceDeletedAt(workspaceId) == null) {
                assertThat(activeOwnerCount(workspaceId)).isGreaterThanOrEqualTo(1);
                assertThat(activeMembershipCount(workspaceId)).isEqualTo(2);
            } else {
                assertThat(activeMembershipCount(workspaceId)).isZero();
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    @DisplayName("초대 발급이 Workspace 잠금 대기 중 마지막 멤버 탈퇴로 삭제되면 초대를 저장하지 않는다")
    @Test
    void leave_success_blocksInvitationIssueUntilDeletedWorkspaceRejected() throws Exception {
        // given
        long ownerMemberId = saveMember("owner");
        long workspaceId = saveWorkspace("탈퇴 발급 경합 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );

        // when
        IssueWhileDeletingWorkspaceResult result = issueWhileDeletingLockedWorkspace(
                ownerMemberId,
                workspaceId
        );

        // then
        assertThat(result.issueWaitedForWorkspaceLock()).isTrue();
        assertThat(result.issueErrorCode()).isEqualTo(WorkspaceErrorCode.WORKSPACE_NOT_FOUND);
        assertThat(invitationCount(workspaceId)).isZero();
        assertThat(workspaceDeletedAt(workspaceId)).isNotNull();
        assertThat(activeMembershipCount(workspaceId)).isZero();
    }

    @DisplayName("마지막 멤버 탈퇴 후 같은 트랜잭션에서 예외가 나면 멤버십 탈퇴와 워크스페이스 삭제를 함께 롤백한다")
    @Test
    void leave_failure_rollsBackLastMemberLeaveWhenOuterTransactionFails() {
        // given
        long memberId = saveMember("rollback");
        long workspaceId = saveWorkspace("롤백 팀");
        long membershipId = saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            workspaceLeaveService.leave(
                    memberId,
                    workspaceId
            );
            throw new IllegalStateException("탈퇴 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(membershipState(membershipId).leftAt()).isNull();
        assertThat(workspaceDeletedAt(workspaceId)).isNull();
        assertThat(activeMembershipCount(workspaceId)).isEqualTo(1);
    }

    @DisplayName("다른 멤버가 남는 탈퇴는 탈퇴자의 진행 중 녹음만 폐기하고 다른 녹음은 보존한다")
    @Test
    void leave_success_discardsLeavingMemberActiveRecording() {
        // given
        long ownerMemberId = saveMember("owner");
        long leavingMemberId = saveMember("leaving");
        long workspaceId = saveWorkspace("녹음 탈퇴 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerMemberId,
                "OWNER"
        );
        long leavingMembershipId = saveWorkspaceMember(
                workspaceId,
                leavingMemberId,
                "MEMBER"
        );
        long leavingRecordingId = saveRecording(
                workspaceId,
                leavingMemberId,
                "RECORDING"
        );
        long endedRecordingId = saveRecording(
                workspaceId,
                leavingMemberId,
                "ENDED"
        );
        long ownerRecordingId = saveRecording(
                workspaceId,
                ownerMemberId,
                "PAUSED"
        );

        // when
        workspaceLeaveService.leave(
                leavingMemberId,
                workspaceId
        );

        // then
        RecordingState discarded = recordingState(leavingRecordingId);
        assertThat(discarded.status()).isEqualTo("DISCARDED");
        assertThat(discarded.endedAt()).isEqualTo(membershipState(leavingMembershipId).leftAt());
        assertThat(recordingState(endedRecordingId).status()).isEqualTo("ENDED");
        assertThat(recordingState(ownerRecordingId).status()).isEqualTo("PAUSED");
    }

    @DisplayName("마지막 멤버 탈퇴는 해당 워크스페이스의 모든 진행 중 녹음을 폐기하고 다른 워크스페이스 녹음은 보존한다")
    @Test
    void leave_success_lastMemberDiscardsWorkspaceRecordings() {
        // given
        long lastMemberId = saveMember("last");
        long formerMemberId = saveMember("former");
        long otherMemberId = saveMember("other");
        long workspaceId = saveWorkspace("녹음 삭제 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                workspaceId,
                lastMemberId,
                "OWNER"
        );
        long lastRecordingId = saveRecording(
                workspaceId,
                lastMemberId,
                "RECORDING"
        );
        long formerRecordingId = saveRecording(
                workspaceId,
                formerMemberId,
                "PAUSED"
        );
        long otherRecordingId = saveRecording(
                otherWorkspaceId,
                otherMemberId,
                "RECORDING"
        );

        // when
        workspaceLeaveService.leave(
                lastMemberId,
                workspaceId
        );

        // then
        assertThat(workspaceDeletedAt(workspaceId)).isNotNull();
        assertThat(recordingState(lastRecordingId).status()).isEqualTo("DISCARDED");
        assertThat(recordingState(formerRecordingId).status()).isEqualTo("DISCARDED");
        assertThat(recordingState(otherRecordingId).status()).isEqualTo("RECORDING");
    }

    @DisplayName("탈퇴 후 같은 트랜잭션에서 예외가 나면 녹음 폐기도 함께 롤백한다")
    @Test
    void leave_failure_rollsBackRecordingDiscardWhenOuterTransactionFails() {
        // given
        long memberId = saveMember("discard-rollback");
        long workspaceId = saveWorkspace("폐기 롤백 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "OWNER"
        );
        long recordingId = saveRecording(
                workspaceId,
                memberId,
                "RECORDING"
        );

        // when
        Throwable thrown = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            workspaceLeaveService.leave(
                    memberId,
                    workspaceId
            );
            throw new IllegalStateException("폐기 후 롤백 검증");
        }));

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        RecordingState recording = recordingState(recordingId);
        assertThat(recording.status()).isEqualTo("RECORDING");
        assertThat(recording.endedAt()).isNull();
    }

    @DisplayName("마지막 멤버 탈퇴로 삭제된 워크스페이스는 기존 초대 미리보기와 참여를 거절한다")
    @ParameterizedTest
    @ValueSource(strings = {"preview", "accept"})
    void invitation_failure_workspaceDeletedByLastMemberLeave(String operation) {
        // given
        long ownerId = saveMember("owner");
        long joiningId = saveMember("joining");
        long workspaceId = saveWorkspace("삭제 초대 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        WorkspaceInvitationResult invitation = workspaceInvitationService.issue(
                workspaceId,
                ownerId
        );
        workspaceLeaveService.leave(
                ownerId,
                workspaceId
        );

        // when
        Throwable thrown = catchThrowable(() -> {
            if (operation.equals("preview")) {
                workspaceInvitationService.preview(
                        invitation.code(),
                        "deleted-preview"
                );
            } else {
                workspaceInvitationService.accept(
                        invitation.code(),
                        "deleted-accept",
                        joiningId
                );
            }
        });

        // then
        assertThat(thrown).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_INVITATION_PREVIEW_NOT_FOUND);
        assertThat(activeMembershipCount(workspaceId)).isZero();
        assertThat(
                workspaceMembershipCount(
                        workspaceId,
                        joiningId
                )
        ).isZero();
    }

    private Callable<WorkspaceErrorCode> outcomeAfterBarrier(
            CyclicBarrier barrier,
            ThrowingOperation operation
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

    private RaceResult awaitRaceResult(
            ExecutorService executorService,
            Callable<WorkspaceErrorCode> leave,
            Callable<WorkspaceErrorCode> updateLastViewed
    ) throws Exception {
        Future<WorkspaceErrorCode> leaveResult = executorService.submit(leave);
        Future<WorkspaceErrorCode> lastViewedResult = executorService.submit(updateLastViewed);
        return new RaceResult(
                leaveResult.get(
                        10,
                        TimeUnit.SECONDS
                ),
                lastViewedResult.get(
                        10,
                        TimeUnit.SECONDS
                )
        );
    }

    private Callable<AcceptOutcome> acceptOutcomeAfterBarrier(
            CyclicBarrier barrier,
            String credential,
            long memberId
    ) {
        return () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            try {
                return AcceptOutcome.accepted(
                        workspaceInvitationService.accept(
                                credential,
                                "accept-race-remote",
                                memberId
                        )
                );
            } catch (WorkspaceException exception) {
                return AcceptOutcome.rejected((WorkspaceErrorCode) exception.getErrorCode());
            }
        };
    }

    private LeaveAcceptRaceResult awaitLeaveAcceptRaceResult(
            ExecutorService executorService,
            Callable<WorkspaceErrorCode> leave,
            Callable<AcceptOutcome> accept
    ) throws Exception {
        Future<WorkspaceErrorCode> leaveResult = executorService.submit(leave);
        Future<AcceptOutcome> acceptResult = executorService.submit(accept);
        return new LeaveAcceptRaceResult(
                leaveResult.get(
                        10,
                        TimeUnit.SECONDS
                ),
                acceptResult.get(
                        10,
                        TimeUnit.SECONDS
                )
        );
    }

    private IssueWhileDeletingWorkspaceResult issueWhileDeletingLockedWorkspace(
            long memberId,
            long workspaceId
    ) throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        AtomicReference<Future<WorkspaceErrorCode>> issueFuture = new AtomicReference<>();
        try {
            boolean issueWaitedForWorkspaceLock = transactionTemplate.execute(status -> {
                int holderBackendPid = lockWorkspace(workspaceId);
                Future<WorkspaceErrorCode> submittedIssue = executorService.submit(
                        () -> issueErrorCode(
                                workspaceId,
                                memberId
                        )
                );
                issueFuture.set(submittedIssue);
                boolean waitedForLock = waitUntilBlockedByHolder(
                        submittedIssue,
                        holderBackendPid
                );
                workspaceLeaveService.leave(
                        memberId,
                        workspaceId
                );
                return waitedForLock;
            });
            return new IssueWhileDeletingWorkspaceResult(
                    issueFuture.get()
                            .get(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                    issueWaitedForWorkspaceLock
            );
        } finally {
            executorService.shutdownNow();
        }
    }

    private WorkspaceErrorCode issueErrorCode(
            long workspaceId,
            long memberId
    ) {
        try {
            workspaceInvitationService.issue(
                    workspaceId,
                    memberId
            );
            return null;
        } catch (WorkspaceException exception) {
            return (WorkspaceErrorCode) exception.getErrorCode();
        }
    }

    private int lockWorkspace(long workspaceId) {
        return jdbcClient.sql("""
                SELECT pg_backend_pid()
                FROM workspaces
                WHERE id = :workspaceId
                FOR UPDATE
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Integer.class)
                .single();
    }

    private boolean waitUntilBlockedByHolder(
            Future<WorkspaceErrorCode> issueFuture,
            int holderBackendPid
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (issueFuture.isDone()) {
                return false;
            }
            if (blockedByHolderExists(holderBackendPid)) {
                return true;
            }
            awaitNextLockProbe();
        }
        return false;
    }

    private boolean blockedByHolderExists(int holderBackendPid) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid()
                      AND :holderBackendPid = ANY(pg_blocking_pids(pid))
                )
                """)
                .param(
                        "holderBackendPid",
                        holderBackendPid
                )
                .query(Boolean.class)
                .single();
    }

    private void awaitNextLockProbe() {
        try {
            TimeUnit.MILLISECONDS.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "Workspace 잠금 대기 확인 중 인터럽트되었습니다",
                    exception
            );
        }
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

    private RecordingState recordingState(long recordingId) {
        return jdbcClient.sql("SELECT status, ended_at FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> new RecordingState(
                                resultSet.getString("status"),
                                Optional.ofNullable(
                                        resultSet.getObject(
                                                "ended_at",
                                                OffsetDateTime.class
                                        )
                                )
                                        .map(OffsetDateTime::toInstant)
                                        .orElse(null)
                        )
                )
                .single();
    }

    private void markLastViewed(long membershipId) {
        jdbcClient.sql("""
                UPDATE workspace_members
                SET last_viewed = TRUE
                WHERE id = :membershipId
                """)
                .param(
                        "membershipId",
                        membershipId
                )
                .update();
    }

    private void leaveMembership(
            long membershipId,
            Instant leftAt
    ) {
        jdbcClient.sql("""
                UPDATE workspace_members
                SET left_at = CAST(:leftAt AS TIMESTAMPTZ),
                    last_viewed = FALSE
                WHERE id = :membershipId
                """)
                .param(
                        "membershipId",
                        membershipId
                )
                .param(
                        "leftAt",
                        leftAt.toString()
                )
                .update();
    }

    private void deleteWorkspace(
            long workspaceId,
            Instant deletedAt
    ) {
        jdbcClient.sql("""
                UPDATE workspaces
                SET deleted_at = CAST(:deletedAt AS TIMESTAMPTZ)
                WHERE id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "deletedAt",
                        deletedAt.toString()
                )
                .update();
    }

    private MembershipState membershipState(long membershipId) {
        return jdbcClient.sql("""
                SELECT id, role, joined_at, left_at, last_viewed
                FROM workspace_members
                WHERE id = :membershipId
                """)
                .param(
                        "membershipId",
                        membershipId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> membershipState(
                                resultSet,
                                rowNumber
                        )
                )
                .single();
    }

    private MembershipState membershipState(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        OffsetDateTime joinedAt = resultSet.getObject(
                "joined_at",
                OffsetDateTime.class
        );
        OffsetDateTime leftAt = resultSet.getObject(
                "left_at",
                OffsetDateTime.class
        );
        return new MembershipState(
                resultSet.getLong("id"),
                resultSet.getString("role"),
                joinedAt.toInstant(),
                leftAt == null ? null : leftAt.toInstant(),
                resultSet.getBoolean("last_viewed")
        );
    }

    private MembershipState latestMembershipState(
            long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT id, role, joined_at, left_at, last_viewed
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND member_id = :memberId
                ORDER BY id DESC
                LIMIT 1
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> membershipState(
                                resultSet,
                                rowNumber
                        )
                )
                .single();
    }

    private Instant workspaceDeletedAt(long workspaceId) {
        return jdbcClient.sql("""
                SELECT deleted_at
                FROM workspaces
                WHERE id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> {
                            OffsetDateTime deletedAt = resultSet.getObject(
                                    "deleted_at",
                                    OffsetDateTime.class
                            );
                            return Optional.ofNullable(deletedAt)
                                    .map(OffsetDateTime::toInstant);
                        }
                )
                .single()
                .orElse(null);
    }

    private long activeMembershipCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND left_at IS NULL
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long lastViewedCount(long memberId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE member_id = :memberId
                  AND last_viewed
                """)
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .single();
    }

    private long workspaceMembershipCount(
            long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND member_id = :memberId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .single();
    }

    private long invitationCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_invitations
                WHERE workspace_id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long activeOwnerCount(long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND role = 'OWNER'
                  AND left_at IS NULL
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private interface ThrowingOperation {

        void run();
    }

    private record RecordingState(
            String status,
            Instant endedAt
    ) {
    }

    private record MembershipState(
            long id,
            String role,
            Instant joinedAt,
            Instant leftAt,
            boolean lastViewed
    ) {
    }

    private record RaceResult(
            WorkspaceErrorCode leaveErrorCode,
            WorkspaceErrorCode lastViewedErrorCode
    ) {
    }

    private record LeaveAcceptRaceResult(
            WorkspaceErrorCode leaveErrorCode,
            AcceptOutcome acceptOutcome
    ) {
    }

    private record IssueWhileDeletingWorkspaceResult(
            WorkspaceErrorCode issueErrorCode,
            boolean issueWaitedForWorkspaceLock
    ) {
    }

    private record AcceptOutcome(
            WorkspaceInvitationAcceptanceResult result,
            WorkspaceErrorCode errorCode
    ) {

        private static AcceptOutcome accepted(WorkspaceInvitationAcceptanceResult result) {
            return new AcceptOutcome(
                    result,
                    null
            );
        }

        private static AcceptOutcome rejected(WorkspaceErrorCode errorCode) {
            return new AcceptOutcome(
                    null,
                    errorCode
            );
        }

        private boolean accepted() {
            return result != null;
        }
    }
}
