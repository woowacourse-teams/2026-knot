package com.knot.backend.workspace.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.WorkspaceDetailQuery;
import com.knot.backend.workspace.application.WorkspaceLeaveService;
import com.knot.backend.workspace.application.dto.result.WorkspaceDetailResult;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceDetailQueryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-09T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-09T00:01:00Z");
    private static final Instant LEFT_AT = Instant.parse("2026-10-09T00:02:00Z");
    private static final Instant REJOINED_AT = Instant.parse("2026-10-09T00:03:00Z");
    private static final int READ_COUNT_DURING_TRANSFER = 50;

    private final WorkspaceDetailQuery workspaceDetailQuery;
    private final WorkspaceLeaveService leaveService;
    private final JdbcClient jdbcClient;

    WorkspaceDetailQueryIntegrationTest(
            WorkspaceDetailQuery workspaceDetailQuery,
            WorkspaceLeaveService leaveService,
            JdbcClient jdbcClient
    ) {
        this.workspaceDetailQuery = workspaceDetailQuery;
        this.leaveService = leaveService;
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

    @Test
    @DisplayName("혼자 남은 OWNER는 OWNER 역할과 활성 멤버 수 1을 조회한다")
    void find_success_soleOwner() {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("혼자 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );

        // when
        Optional<WorkspaceDetailResult> snapshot = workspaceDetailQuery.find(
                workspaceId,
                ownerId
        );

        // then
        assertThat(snapshot).contains(
                new WorkspaceDetailResult(
                        "혼자 팀",
                        WorkspaceMemberRole.OWNER,
                        1L
                )
        );
    }

    @Test
    @DisplayName("멤버가 둘이면 각 호출자의 역할과 활성 멤버 수 2를 조회한다")
    void find_success_rolePerCaller() {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("두 명 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );

        // when
        List<Optional<WorkspaceDetailResult>> snapshots = List.of(
                workspaceDetailQuery.find(
                        workspaceId,
                        ownerId
                ),
                workspaceDetailQuery.find(
                        workspaceId,
                        memberId
                )
        );

        // then
        assertThat(snapshots).containsExactly(
                Optional.of(
                        new WorkspaceDetailResult(
                                "두 명 팀",
                                WorkspaceMemberRole.OWNER,
                                2L
                        )
                ),
                Optional.of(
                        new WorkspaceDetailResult(
                                "두 명 팀",
                                WorkspaceMemberRole.MEMBER,
                                2L
                        )
                )
        );
    }

    @Test
    @DisplayName("탈퇴한 멤버는 활성 멤버 수에서 빼고 재가입한 멤버는 한 명으로 센다")
    void find_success_excludesLeftAndCountsRejoinedOnce() {
        // given
        long ownerId = saveMember("owner");
        long leftMemberId = saveMember("left");
        long rejoinedMemberId = saveMember("rejoined");
        long workspaceId = saveWorkspace("이력 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                leftMemberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                rejoinedMemberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        markMembershipLeft(
                workspaceId,
                leftMemberId
        );
        markMembershipLeft(
                workspaceId,
                rejoinedMemberId
        );
        saveWorkspaceMember(
                workspaceId,
                rejoinedMemberId,
                WorkspaceMemberRole.MEMBER,
                REJOINED_AT
        );

        // when
        Optional<WorkspaceDetailResult> snapshot = workspaceDetailQuery.find(
                workspaceId,
                rejoinedMemberId
        );

        // then
        assertThat(snapshot).contains(
                new WorkspaceDetailResult(
                        "이력 팀",
                        WorkspaceMemberRole.MEMBER,
                        2L
                )
        );
    }

    @Test
    @DisplayName("탈퇴한 회원이 조회하면 역할 없이 남은 활성 멤버 수를 조회한다")
    void find_success_leftMemberHasNoRole() {
        // given
        long ownerId = saveMember("owner");
        long leftMemberId = saveMember("left");
        long workspaceId = saveWorkspace("탈퇴 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                leftMemberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        markMembershipLeft(
                workspaceId,
                leftMemberId
        );

        // when
        Optional<WorkspaceDetailResult> snapshot = workspaceDetailQuery.find(
                workspaceId,
                leftMemberId
        );

        // then
        assertThat(snapshot).contains(
                new WorkspaceDetailResult(
                        "탈퇴 팀",
                        null,
                        1L
                )
        );
    }

    @Test
    @DisplayName("삭제된 워크스페이스는 조회 결과가 없다")
    void find_success_deletedWorkspaceIsEmpty() {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("삭제 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        markMembershipLeft(
                workspaceId,
                ownerId
        );
        markWorkspaceDeleted(workspaceId);

        // when
        Optional<WorkspaceDetailResult> snapshot = workspaceDetailQuery.find(
                workspaceId,
                ownerId
        );

        // then
        assertThat(snapshot).isEmpty();
    }

    @Test
    @DisplayName("승계와 동시에 조회해도 승계 전이나 후 한 시점의 역할과 멤버 수만 반환한다")
    void find_success_consistentDuringOwnershipTransfer() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("승계 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        WorkspaceDetailResult beforeTransfer = new WorkspaceDetailResult(
                "승계 팀",
                WorkspaceMemberRole.MEMBER,
                2L
        );
        WorkspaceDetailResult afterTransfer = new WorkspaceDetailResult(
                "승계 팀",
                WorkspaceMemberRole.OWNER,
                1L
        );

        // when
        List<WorkspaceDetailResult> snapshots = readDuringTransfer(
                workspaceId,
                ownerId,
                successorId
        );

        // then
        assertThat(snapshots).hasSize(READ_COUNT_DURING_TRANSFER)
                .allSatisfy(
                        snapshot -> assertThat(snapshot).isIn(
                                beforeTransfer,
                                afterTransfer
                        )
                );
        assertThat(
                workspaceDetailQuery.find(
                        workspaceId,
                        successorId
                )
        ).contains(afterTransfer);
    }

    private List<WorkspaceDetailResult> readDuringTransfer(
            long workspaceId,
            long ownerId,
            long successorId
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<?> transfer = executorService.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                leaveService.transferOwnershipAndLeave(
                        ownerId,
                        workspaceId,
                        successorId
                );
                return null;
            });
            Future<List<WorkspaceDetailResult>> reads = executorService.submit(() -> {
                barrier.await(
                        5,
                        TimeUnit.SECONDS
                );
                List<WorkspaceDetailResult> snapshots = new ArrayList<>();
                for (int i = 0; i < READ_COUNT_DURING_TRANSFER; i++) {
                    snapshots.add(
                            workspaceDetailQuery.find(
                                    workspaceId,
                                    successorId
                            )
                                    .orElseThrow()
                    );
                }
                return snapshots;
            });
            transfer.get(
                    10,
                    TimeUnit.SECONDS
            );
            return reads.get(
                    10,
                    TimeUnit.SECONDS
            );
        } finally {
            executorService.shutdownNow();
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

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId,
            WorkspaceMemberRole role,
            Instant joinedAt
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
                        role.name()
                )
                .param(
                        "joinedAt",
                        joinedAt.toString()
                )
                .update();
    }

    private void markMembershipLeft(
            long workspaceId,
            long memberId
    ) {
        jdbcClient.sql("""
                UPDATE workspace_members
                SET left_at = CAST(:leftAt AS TIMESTAMPTZ), last_viewed = FALSE
                WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
                """)
                .param(
                        "leftAt",
                        LEFT_AT.toString()
                )
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

    private void markWorkspaceDeleted(long workspaceId) {
        jdbcClient.sql("""
                UPDATE workspaces
                SET deleted_at = CAST(:deletedAt AS TIMESTAMPTZ)
                WHERE id = :workspaceId
                """)
                .param(
                        "deletedAt",
                        LEFT_AT.toString()
                )
                .param(
                        "workspaceId",
                        workspaceId
                )
                .update();
    }
}
