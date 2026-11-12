package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.member.domain.Member;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationAcceptanceResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;

@Tag("integration")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceInvitationAcceptanceServiceIntegrationTest {
    private final WorkspaceInvitationService invitationService;
    private final MemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final JdbcClient jdbcClient;

    WorkspaceInvitationAcceptanceServiceIntegrationTest(
            WorkspaceInvitationService invitationService,
            MemberRepository memberRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            JdbcClient jdbcClient
    ) {
        this.invitationService = invitationService;
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.jdbcClient = jdbcClient;
    }

    @DisplayName("같은 사용자의 동시 초대 참여는 멤버십 하나와 201·200 결과로 수렴한다")
    @Test
    void accept_success_concurrentSameMemberRequestsConverge() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("동시 중복 참여 팀");
        Member joiningMember = createMember();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<WorkspaceInvitationAcceptanceResult> acceptInvitation = () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            return invitationService.accept(
                    fixture.invitation()
                            .code(),
                    uniqueRemoteAddress(),
                    joiningMember.getId()
            );
        };

        try {
            // when
            List<WorkspaceInvitationAcceptanceResult> results = awaitAcceptanceResults(
                    executorService,
                    acceptInvitation
            );

            // then
            assertThat(results).extracting(WorkspaceInvitationAcceptanceResult::membershipCreated)
                    .containsExactlyInAnyOrder(
                            true,
                            false
                    );
            assertThat(
                    countMemberships(
                            fixture.workspaceId(),
                            joiningMember.getId()
                    )
            ).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    @DisplayName("서로 다른 두 사용자는 같은 초대로 각각 MEMBER 멤버십을 만든다")
    @Test
    void accept_success_concurrentDifferentMembersKeepInvitationValid() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("동시 다중 참여 팀");
        Member firstMember = createMember();
        Member secondMember = createMember();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<WorkspaceInvitationAcceptanceResult> firstAcceptance = () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            return invitationService.accept(
                    fixture.invitation()
                            .linkToken(),
                    uniqueRemoteAddress(),
                    firstMember.getId()
            );
        };
        Callable<WorkspaceInvitationAcceptanceResult> secondAcceptance = () -> {
            barrier.await(
                    5,
                    TimeUnit.SECONDS
            );
            return invitationService.accept(
                    fixture.invitation()
                            .linkToken(),
                    uniqueRemoteAddress(),
                    secondMember.getId()
            );
        };

        try {
            // when
            List<WorkspaceInvitationAcceptanceResult> results = awaitAcceptanceResults(
                    executorService,
                    firstAcceptance,
                    secondAcceptance
            );

            // then
            assertThat(results).extracting(WorkspaceInvitationAcceptanceResult::membershipCreated)
                    .containsOnly(true);
            assertThat(
                    countMemberships(
                            fixture.workspaceId(),
                            firstMember.getId()
                    )
            ).isEqualTo(1);
            assertThat(
                    countMemberships(
                            fixture.workspaceId(),
                            secondMember.getId()
                    )
            ).isEqualTo(1);
            assertThat(countUninvalidatedInvitations(fixture.workspaceId())).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    @DisplayName("멤버십 저장이 실패하면 초대와 기존 멤버십 상태를 함께 보존한다")
    @Test
    void accept_failure_rollsBackWhenMembershipSaveFails() {
        // given
        InvitationFixture fixture = createInvitationFixture("참여 롤백 팀");
        long missingMemberId = Long.MAX_VALUE;
        List<String> invitationSnapshot = invitationSnapshot(fixture.workspaceId());
        long membershipCount = countAllMemberships(fixture.workspaceId());

        // when
        Throwable thrown = catchThrowable(
                () -> invitationService.accept(
                        fixture.invitation()
                                .linkToken(),
                        uniqueRemoteAddress(),
                        missingMemberId
                )
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countAllMemberships(fixture.workspaceId())).isEqualTo(membershipCount);
        assertThat(invitationSnapshot(fixture.workspaceId())).isEqualTo(invitationSnapshot);
    }

    private InvitationFixture createInvitationFixture(String workspaceName) {
        Instant now = Instant.now()
                .minusSeconds(1);
        Member owner = createMember();
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        workspaceName,
                        now
                )
        );
        workspaceMemberRepository.save(
                WorkspaceMember.create(
                        workspace.getId(),
                        owner.getId(),
                        WorkspaceMemberRole.OWNER,
                        now
                )
        );
        WorkspaceInvitationResult invitation = invitationService.issue(
                workspace.getId(),
                owner.getId()
        );
        return new InvitationFixture(
                workspace.getId(),
                owner.getId(),
                invitation
        );
    }

    private Member createMember() {
        return memberRepository.save(
                Member.create(
                        uniqueNickname(),
                        null
                )
        );
    }

    private List<WorkspaceInvitationAcceptanceResult> awaitAcceptanceResults(
            ExecutorService executorService,
            Callable<WorkspaceInvitationAcceptanceResult> acceptance
    ) throws Exception {
        return awaitAcceptanceResults(
                executorService,
                acceptance,
                acceptance
        );
    }

    private List<WorkspaceInvitationAcceptanceResult> awaitAcceptanceResults(
            ExecutorService executorService,
            Callable<WorkspaceInvitationAcceptanceResult> firstAcceptance,
            Callable<WorkspaceInvitationAcceptanceResult> secondAcceptance
    ) throws Exception {
        Future<WorkspaceInvitationAcceptanceResult> first = executorService.submit(firstAcceptance);
        Future<WorkspaceInvitationAcceptanceResult> second = executorService.submit(secondAcceptance);
        return List.of(
                first.get(
                        10,
                        TimeUnit.SECONDS
                ),
                second.get(
                        10,
                        TimeUnit.SECONDS
                )
        );
    }

    private long countMemberships(
            Long workspaceId,
            Long memberId
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

    private long countAllMemberships(Long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private long countUninvalidatedInvitations(Long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_invitations
                WHERE workspace_id = :workspaceId
                  AND invalidated_at IS NULL
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private List<String> invitationSnapshot(Long workspaceId) {
        return jdbcClient.sql("""
                SELECT to_jsonb(invitation)::text
                FROM workspace_invitations invitation
                WHERE workspace_id = :workspaceId
                ORDER BY id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .list();
    }

    private String uniqueNickname() {
        return "member" + UUID.randomUUID()
                .toString()
                .replace(
                        "-",
                        ""
                )
                .replaceAll(
                        "[0-9]",
                        "g"
                )
                .substring(
                        0,
                        10
                );
    }

    private String uniqueRemoteAddress() {
        return "test-" + UUID.randomUUID();
    }

    private record InvitationFixture(
            Long workspaceId,
            long ownerMemberId,
            WorkspaceInvitationResult invitation
    ) {
    }

}
