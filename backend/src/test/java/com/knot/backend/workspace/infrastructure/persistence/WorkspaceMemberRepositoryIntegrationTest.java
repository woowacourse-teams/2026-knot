package com.knot.backend.workspace.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@IsolatedPostgresTest
@Import({WorkspaceRepositoryAdapter.class, WorkspaceMemberRepositoryAdapter.class})
@DataJpaTest
class WorkspaceMemberRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-24T00:01:00Z");

    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;

    @DisplayName("멤버별 마지막으로 본 워크스페이스 멤버십은 하나만 저장할 수 있다")
    @Test
    void save_failure_duplicateLastViewedMember() {
        // given
        long memberId = saveMember(1L);
        Workspace firstWorkspace = saveAndFlush(
                Workspace.create(
                        "첫 팀",
                        CREATED_AT
                )
        );
        Workspace secondWorkspace = saveAndFlush(
                Workspace.create(
                        "두 번째 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember firstWorkspaceMember = WorkspaceMember.create(
                firstWorkspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        firstWorkspaceMember.markLastViewed();
        saveAndFlush(firstWorkspaceMember);
        WorkspaceMember secondWorkspaceMember = WorkspaceMember.create(
                secondWorkspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        secondWorkspaceMember.markLastViewed();

        // when
        ThrowingCallable action = () -> saveAndFlush(secondWorkspaceMember);

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("멤버별 마지막으로 본 워크스페이스 멤버십을 조회한다")
    @Test
    void findLastViewedByMemberId_success() {
        // given
        long memberId = saveMember(2L);
        Workspace firstWorkspace = saveAndFlush(
                Workspace.create(
                        "첫 팀",
                        CREATED_AT
                )
        );
        Workspace secondWorkspace = saveAndFlush(
                Workspace.create(
                        "마지막 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        firstWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER,
                        JOINED_AT
                )
        );
        WorkspaceMember lastViewedWorkspaceMember = WorkspaceMember.create(
                secondWorkspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        lastViewedWorkspaceMember.markLastViewed();
        saveAndFlush(lastViewedWorkspaceMember);
        entityManager.clear();

        // when
        WorkspaceMember foundWorkspaceMember = workspaceMemberRepository.findLastViewedByMemberId(memberId)
                .orElseThrow();

        // then
        assertThat(foundWorkspaceMember.getWorkspaceId()).isEqualTo(secondWorkspace.getId());
        assertThat(foundWorkspaceMember.isLastViewed()).isTrue();
    }

    @DisplayName("멤버십을 탈퇴하면 마지막 조회 상태도 함께 사라진다")
    @Test
    void findLastViewedByMemberId_success_leftMembership() {
        // given
        long memberId = saveMember(3L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember workspaceMember = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        workspaceMember.markLastViewed();
        WorkspaceMember savedWorkspaceMember = saveAndFlush(workspaceMember);
        jdbcClient.sql("""
                UPDATE workspace_members
                SET last_viewed = FALSE, left_at = :leftAt
                WHERE id = :workspaceMemberId
                """)
                .param(
                        "workspaceMemberId",
                        savedWorkspaceMember.getId()
                )
                .param(
                        "leftAt",
                        Timestamp.from(JOINED_AT.plusSeconds(1))
                )
                .update();
        entityManager.clear();

        // when
        boolean exists = workspaceMemberRepository.findLastViewedByMemberId(memberId)
                .isPresent();

        // then
        assertThat(exists).isFalse();
    }

    @DisplayName("탈퇴한 멤버십은 같은 워크스페이스에 새 활성 멤버십을 다시 저장할 수 있다")
    @Test
    void save_success_rejoinAfterLeftMembership() {
        // given
        long memberId = saveMember(8L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "재가입 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember leftWorkspaceMember = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        leftWorkspaceMember.leave(
                JOINED_AT.plusSeconds(1),
                2L
        );
        saveAndFlush(leftWorkspaceMember);
        WorkspaceMember rejoinedWorkspaceMember = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT.plusSeconds(2)
        );

        // when
        WorkspaceMember savedWorkspaceMember = saveAndFlush(rejoinedWorkspaceMember);

        // then
        assertThat(savedWorkspaceMember.getLeftAt()).isNull();
        assertThat(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        workspace.getId(),
                        memberId
                )
        ).isTrue();
    }

    @DisplayName("탈퇴한 멤버십은 활성 멤버십 조회와 활성 수에서 제외한다")
    @Test
    void activeQueries_success_excludeLeftMemberships() {
        // given
        long memberId = saveMember(9L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "탈퇴 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember workspaceMember = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
        workspaceMember.leave(
                JOINED_AT.plusSeconds(1),
                2L
        );
        saveAndFlush(workspaceMember);
        entityManager.clear();

        // when
        boolean exists = workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                workspace.getId(),
                memberId
        );

        // then
        assertThat(exists).isFalse();
        assertThat(workspaceMemberRepository.countActiveByWorkspaceId(workspace.getId())).isZero();
    }

    @DisplayName("멤버의 워크스페이스 멤버십을 ID 오름차순으로 잠금 조회한다")
    @Test
    void findAllByMemberIdForUpdate_success_ordersById() {
        // given
        long memberId = saveMember(4L);
        Workspace firstWorkspace = saveAndFlush(
                Workspace.create(
                        "첫 팀",
                        CREATED_AT
                )
        );
        Workspace secondWorkspace = saveAndFlush(
                Workspace.create(
                        "두 번째 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember firstWorkspaceMember = saveAndFlush(
                WorkspaceMember.create(
                        firstWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        WorkspaceMember secondWorkspaceMember = saveAndFlush(
                WorkspaceMember.create(
                        secondWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER,
                        JOINED_AT
                )
        );
        entityManager.clear();

        // when
        List<WorkspaceMember> workspaceMembers = workspaceMemberRepository.findAllByMemberIdForUpdate(memberId);

        // then
        assertThat(workspaceMembers).extracting(WorkspaceMember::getId)
                .containsExactly(
                        firstWorkspaceMember.getId(),
                        secondWorkspaceMember.getId()
                );
    }

    @DisplayName("삭제된 워크스페이스의 활성 행도 멤버 권한으로 인정하지 않는다")
    @Test
    void existsByWorkspaceIdAndMemberId_failure_deletedWorkspace() {
        // given
        long memberId = saveMember(10L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember membership = saveAndFlush(
                WorkspaceMember.create(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        membership.markLastViewed();
        workspace.delete(JOINED_AT.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // when
        boolean exists = workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                workspace.getId(),
                memberId
        );

        // then
        assertThat(exists).isFalse();
    }

    @DisplayName("삭제된 워크스페이스의 OWNER 행은 활성 역할 검사에서 제외한다")
    @Test
    void existsByWorkspaceIdAndMemberIdAndRole_failure_deletedWorkspace() {
        // given
        long memberId = saveMember(11L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        workspace.delete(JOINED_AT.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // when
        boolean exists = workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER
        );

        // then
        assertThat(exists).isFalse();
    }

    @DisplayName("삭제된 워크스페이스는 마지막 조회 포인터에서 제외한다")
    @Test
    void findLastViewedByMemberId_success_excludesDeletedWorkspace() {
        // given
        long memberId = saveMember(12L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember membership = saveAndFlush(
                WorkspaceMember.create(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        membership.markLastViewed();
        workspace.delete(JOINED_AT.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // when
        boolean exists = workspaceMemberRepository.findLastViewedByMemberId(memberId)
                .isPresent();

        // then
        assertThat(exists).isFalse();
    }

    @DisplayName("삭제된 워크스페이스는 마지막 조회 갱신 잠금 목록에서 제외한다")
    @Test
    void findAllByMemberIdForUpdate_success_excludesDeletedWorkspace() {
        // given
        long memberId = saveMember(13L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        workspace.delete(JOINED_AT.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        // when
        List<WorkspaceMember> memberships = workspaceMemberRepository.findAllByMemberIdForUpdate(memberId);

        // then
        assertThat(memberships).isEmpty();
    }

    private long saveMember(long memberNumber) {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES (:nickname, NULL)
                RETURNING id
                """)
                .param(
                        "nickname",
                        "member" + memberNumber
                )
                .query(Long.class)
                .single();
    }

    private Workspace saveAndFlush(Workspace workspace) {
        Workspace savedWorkspace = workspaceRepository.save(workspace);
        entityManager.flush();
        return savedWorkspace;
    }

    private WorkspaceMember saveAndFlush(WorkspaceMember workspaceMember) {
        WorkspaceMember savedWorkspaceMember = workspaceMemberRepository.save(workspaceMember);
        entityManager.flush();
        return savedWorkspaceMember;
    }
}
