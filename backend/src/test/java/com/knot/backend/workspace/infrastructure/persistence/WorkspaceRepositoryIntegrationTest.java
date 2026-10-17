package com.knot.backend.workspace.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.model.Workspace;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Import({TestcontainersConfiguration.class, WorkspaceRepositoryAdapter.class, WorkspaceMemberRepositoryAdapter.class})
@DataJpaTest
class WorkspaceRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-24T00:01:00Z");
    private static final Instant RECENT_JOINED_AT = Instant.parse("2026-08-24T00:02:00Z");

    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;

    @DisplayName("워크스페이스와 멤버십을 저장하고 조회한다")
    @Test
    void saveAndFind_success() {
        // given
        long memberId = saveMember(1L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember workspaceMember = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );

        // when
        WorkspaceMember savedWorkspaceMember = saveAndReload(workspaceMember);

        // then
        assertThat(workspaceRepository.findById(workspace.getId())).get()
                .extracting(
                        Workspace::getName,
                        Workspace::getCreatedAt
                )
                .containsExactly(
                        "Knot 팀",
                        CREATED_AT
                );
        assertThat(
                entityManager.find(
                        WorkspaceMember.class,
                        savedWorkspaceMember.getId()
                )
        ).isNotNull()
                .extracting(
                        WorkspaceMember::getMemberId,
                        WorkspaceMember::getJoinedAt
                )
                .containsExactly(
                        memberId,
                        JOINED_AT
                );
        assertThat(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberId(
                        workspace.getId(),
                        memberId
                )
        ).isTrue();
        assertThat(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER
                )
        ).isTrue();
        assertThat(
                workspaceMemberRepository.existsByWorkspaceIdAndMemberIdAndRole(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER
                )
        ).isFalse();
    }

    @DisplayName("멤버의 활성 OWNER·MEMBER 워크스페이스만 최근 참여 순서로 조회한다")
    @Test
    void findAllByMemberId_success_filtersAndSortsMemberships() {
        // given
        long memberId = saveMember(5L);
        long otherMemberId = saveMember(6L);
        Workspace olderWorkspace = saveAndFlush(
                Workspace.create(
                        "이전 팀",
                        CREATED_AT
                )
        );
        Workspace tiedLowerWorkspace = saveAndFlush(
                Workspace.create(
                        "최근 한 팀",
                        CREATED_AT
                )
        );
        Workspace tiedHigherWorkspace = saveAndFlush(
                Workspace.create(
                        "최근 두 팀",
                        CREATED_AT
                )
        );
        Workspace otherWorkspace = saveAndFlush(
                Workspace.create(
                        "다른 팀",
                        CREATED_AT
                )
        );
        Workspace leftWorkspace = saveAndFlush(
                Workspace.create(
                        "탈퇴 팀",
                        CREATED_AT
                )
        );
        Workspace deletedWorkspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        olderWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        JOINED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        tiedLowerWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER,
                        RECENT_JOINED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        tiedHigherWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.OWNER,
                        RECENT_JOINED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        otherWorkspace.getId(),
                        otherMemberId,
                        WorkspaceMemberRole.MEMBER,
                        RECENT_JOINED_AT
                )
        );
        WorkspaceMember leftWorkspaceMember = WorkspaceMember.create(
                leftWorkspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                RECENT_JOINED_AT
        );
        leftWorkspaceMember.leave(
                RECENT_JOINED_AT.plusSeconds(1),
                2L
        );
        saveAndFlush(leftWorkspaceMember);
        deletedWorkspace.delete(RECENT_JOINED_AT.plusSeconds(1));
        workspaceRepository.save(deletedWorkspace);
        saveAndFlush(
                WorkspaceMember.create(
                        deletedWorkspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER,
                        RECENT_JOINED_AT
                )
        );
        entityManager.clear();

        // when
        List<Workspace> workspaces = workspaceRepository.findAllByMemberId(memberId);

        // then
        assertThat(workspaces).extracting(
                Workspace::getId,
                Workspace::getName
        )
                .containsExactly(
                        tuple(
                                tiedHigherWorkspace.getId(),
                                "최근 두 팀"
                        ),
                        tuple(
                                tiedLowerWorkspace.getId(),
                                "최근 한 팀"
                        ),
                        tuple(
                                olderWorkspace.getId(),
                                "이전 팀"
                        )
                );
    }

    @DisplayName("소속 워크스페이스가 없으면 빈 목록을 조회한다")
    @Test
    void findAllByMemberId_success_emptyMemberships() {
        // given
        long memberId = saveMember(7L);

        // when
        List<Workspace> workspaces = workspaceRepository.findAllByMemberId(memberId);

        // then
        assertThat(workspaces).isEmpty();
    }

    @DisplayName("논리 삭제된 워크스페이스는 단건 조회와 잠금 조회에서 제외한다")
    @Test
    void findById_success_excludeDeletedWorkspace() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "삭제 팀",
                        CREATED_AT
                )
        );
        workspace.delete(CREATED_AT.plusSeconds(1));
        workspaceRepository.save(workspace);
        entityManager.flush();
        entityManager.clear();

        // when
        boolean foundById = workspaceRepository.findById(workspace.getId())
                .isPresent();
        boolean foundByIdForUpdate = workspaceRepository.findByIdForUpdate(workspace.getId())
                .isPresent();

        // then
        assertThat(foundById).isFalse();
        assertThat(foundByIdForUpdate).isFalse();
    }

    @DisplayName("같은 워크스페이스와 활성 멤버 조합은 중복 저장할 수 없다")
    @Test
    void save_failure_duplicateWorkspaceMember() {
        // given
        long memberId = saveMember(2L);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                WorkspaceMember.create(
                        workspace.getId(),
                        memberId,
                        WorkspaceMemberRole.MEMBER,
                        JOINED_AT
                )
        );
        WorkspaceMember duplicate = WorkspaceMember.create(
                workspace.getId(),
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(duplicate);

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("같은 워크스페이스와 멤버 조합을 동시에 저장해도 하나만 성공한다")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void save_failure_concurrentDuplicateWorkspaceMember() throws Exception {
        // given
        long memberId = saveMember(3L);
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        "Knot 동시성 팀",
                        CREATED_AT
                )
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<Boolean> saveMembership = () -> {
            barrier.await();
            try {
                workspaceMemberRepository.save(
                        WorkspaceMember.create(
                                workspace.getId(),
                                memberId,
                                WorkspaceMemberRole.MEMBER,
                                JOINED_AT
                        )
                );
                return true;
            } catch (DataIntegrityViolationException exception) {
                return false;
            }
        };

        try {
            // when
            Future<Boolean> firstResult = executorService.submit(saveMembership);
            Future<Boolean> secondResult = executorService.submit(saveMembership);
            List<Boolean> results = List.of(
                    firstResult.get(),
                    secondResult.get()
            );

            // then
            assertThat(results).containsExactlyInAnyOrder(
                    true,
                    false
            );
        } finally {
            executorService.shutdownNow();
        }
    }

    @DisplayName("존재하지 않는 워크스페이스를 참조하는 멤버십은 저장할 수 없다")
    @Test
    void save_failure_missingWorkspaceReference() {
        // given
        long memberId = saveMember(4L);
        WorkspaceMember workspaceMember = WorkspaceMember.create(
                Long.MAX_VALUE,
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(workspaceMember);

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("존재하지 않는 멤버를 참조하는 멤버십은 저장할 수 없다")
    @Test
    void save_failure_missingMemberReference() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );
        WorkspaceMember workspaceMember = WorkspaceMember.create(
                workspace.getId(),
                Long.MAX_VALUE,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(workspaceMember);

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
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

    private WorkspaceMember saveAndReload(WorkspaceMember workspaceMember) {
        WorkspaceMember savedWorkspaceMember = saveAndFlush(workspaceMember);
        entityManager.clear();
        return entityManager.find(
                WorkspaceMember.class,
                savedWorkspaceMember.getId()
        );
    }
}
