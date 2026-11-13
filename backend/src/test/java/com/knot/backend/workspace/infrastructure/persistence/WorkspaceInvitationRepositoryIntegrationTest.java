package com.knot.backend.workspace.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceInvitation;
import com.knot.backend.workspace.domain.repository.WorkspaceInvitationRepository;
import com.knot.backend.workspace.domain.WorkspaceInvitationSecretCollisionException;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
@IsolatedPostgresTest
@Import({WorkspaceRepositoryAdapter.class, WorkspaceInvitationRepositoryAdapter.class})
@DataJpaTest
class WorkspaceInvitationRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-29T00:00:00Z");
    private static final Instant CREATED_AT_WITH_NANOS = Instant.parse("2026-08-29T00:00:00.123456789Z");
    private static final String LINK_TOKEN_HASH = "link-token-hash";
    private static final String INVITE_CODE_HASH = "invite-code-hash";

    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceInvitationRepository workspaceInvitationRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;

    @DisplayName("워크스페이스 초대를 저장하고 링크 토큰 해시와 초대 코드 해시로 조회한다")
    @Test
    void saveAndFindByHashes_success() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );
        WorkspaceInvitation invitation = createInvitation(
                workspace.getId(),
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT
        );

        // when
        WorkspaceInvitation savedInvitation = saveAndReload(invitation);

        // then
        assertThat(workspaceInvitationRepository.findByLinkTokenHash(LINK_TOKEN_HASH)).get()
                .extracting(WorkspaceInvitation::getId)
                .isEqualTo(savedInvitation.getId());
        assertThat(workspaceInvitationRepository.findByInviteCodeHash(INVITE_CODE_HASH)).get()
                .extracting(WorkspaceInvitation::getId)
                .isEqualTo(savedInvitation.getId());
    }

    @DisplayName("같은 링크 토큰 해시는 서로 다른 워크스페이스에서도 중복 저장할 수 없다")
    @Test
    void save_failure_duplicateLinkTokenHash() {
        // given
        Workspace firstWorkspace = saveAndFlush(
                Workspace.create(
                        "첫 팀",
                        CREATED_AT
                )
        );
        Workspace secondWorkspace = saveAndFlush(
                Workspace.create(
                        "둘째 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                createInvitation(
                        firstWorkspace.getId(),
                        LINK_TOKEN_HASH,
                        "first-code-hash",
                        CREATED_AT
                )
        );
        WorkspaceInvitation duplicate = createInvitation(
                secondWorkspace.getId(),
                LINK_TOKEN_HASH,
                "second-code-hash",
                CREATED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(duplicate);

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceInvitationSecretCollisionException.class);
    }

    @DisplayName("같은 초대 코드 해시는 서로 다른 워크스페이스에서도 중복 저장할 수 없다")
    @Test
    void save_failure_duplicateInviteCodeHash() {
        // given
        Workspace firstWorkspace = saveAndFlush(
                Workspace.create(
                        "첫 팀",
                        CREATED_AT
                )
        );
        Workspace secondWorkspace = saveAndFlush(
                Workspace.create(
                        "둘째 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                createInvitation(
                        firstWorkspace.getId(),
                        "first-link-hash",
                        INVITE_CODE_HASH,
                        CREATED_AT
                )
        );
        WorkspaceInvitation duplicate = createInvitation(
                secondWorkspace.getId(),
                "second-link-hash",
                INVITE_CODE_HASH,
                CREATED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(duplicate);

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceInvitationSecretCollisionException.class);
    }

    @DisplayName("한 워크스페이스에 미무효화 초대를 여러 개 저장한다")
    @Test
    void save_success_multipleUninvalidatedInvitations() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );
        saveAndFlush(
                createInvitation(
                        workspace.getId(),
                        "first-link-hash",
                        "first-code-hash",
                        CREATED_AT
                )
        );
        WorkspaceInvitation duplicate = createInvitation(
                workspace.getId(),
                "second-link-hash",
                "second-code-hash",
                CREATED_AT.plusSeconds(1)
        );

        // when
        WorkspaceInvitation saved = saveAndFlush(duplicate);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(countInvitations(workspace.getId())).isEqualTo(2);
    }

    @DisplayName("만료된 초대를 무효화하지 않고 새 초대를 저장한다")
    @Test
    void save_success_preservesExpiredInvitation() {
        // given
        Instant previousCreatedAt = CREATED_AT.minus(WorkspaceInvitation.VALIDITY_PERIOD)
                .minusSeconds(1);
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        previousCreatedAt
                )
        );
        saveAndFlush(
                createInvitation(
                        workspace.getId(),
                        "expired-link-hash",
                        "expired-code-hash",
                        previousCreatedAt
                )
        );
        WorkspaceInvitation newInvitation = createInvitation(
                workspace.getId(),
                "new-link-hash",
                "new-code-hash",
                CREATED_AT
        );

        // when
        WorkspaceInvitation saved = saveAndFlush(newInvitation);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(countInvitations(workspace.getId())).isEqualTo(2);
    }

    @DisplayName("존재하지 않는 워크스페이스를 참조하는 초대는 저장할 수 없다")
    @Test
    void save_failure_missingWorkspaceReference() {
        // given
        WorkspaceInvitation invitation = createInvitation(
                Long.MAX_VALUE,
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT
        );

        // when
        ThrowingCallable action = () -> saveAndFlush(invitation);

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("만료 시각이 생성 시각으로부터 24시간보다 짧으면 초대를 저장할 수 없다")
    @Test
    void save_failure_expirationLessThanTwentyFourHours() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );

        // when
        ThrowingCallable action = () -> insertInvitation(
                workspace.getId(),
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT.plus(WorkspaceInvitation.VALIDITY_PERIOD)
                        .minusSeconds(1),
                null,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("만료 시각이 생성 시각으로부터 24시간보다 길면 초대를 저장할 수 없다")
    @Test
    void save_failure_expirationGreaterThanTwentyFourHours() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );

        // when
        ThrowingCallable action = () -> insertInvitation(
                workspace.getId(),
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT.plus(WorkspaceInvitation.VALIDITY_PERIOD)
                        .plusSeconds(1),
                null,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("무효화 시각이 생성 시각보다 이르면 초대를 저장할 수 없다")
    @Test
    void save_failure_invalidInvalidation() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT
                )
        );

        // when
        ThrowingCallable action = () -> insertInvitation(
                workspace.getId(),
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT.plus(WorkspaceInvitation.VALIDITY_PERIOD),
                CREATED_AT.minusSeconds(1),
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("나노초 생성 시각으로 저장해도 재조회한 만료 시각과 생성 시각이 일치한다")
    @Test
    void saveAndFind_success_preservesMicrosecondPrecision() {
        // given
        Workspace workspace = saveAndFlush(
                Workspace.create(
                        "Knot 팀",
                        CREATED_AT_WITH_NANOS
                )
        );
        WorkspaceInvitation invitation = createInvitation(
                workspace.getId(),
                LINK_TOKEN_HASH,
                INVITE_CODE_HASH,
                CREATED_AT_WITH_NANOS
        );

        // when
        WorkspaceInvitation savedInvitation = saveAndReload(invitation);

        // then
        assertThat(savedInvitation.getCreatedAt()).isEqualTo(invitation.getCreatedAt());
        assertThat(savedInvitation.getExpiresAt()).isEqualTo(invitation.getExpiresAt());
    }

    @DisplayName("같은 워크스페이스에 미무효화 초대를 동시에 저장하면 모두 성공한다")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void save_success_concurrentUninvalidatedInvitations() throws Exception {
        // given
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        "동시성 팀",
                        CREATED_AT
                )
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<Boolean> saveInvitation = () -> {
            long threadId = Thread.currentThread()
                    .threadId();
            barrier.await();
            try {
                workspaceInvitationRepository.save(
                        createInvitation(
                                workspace.getId(),
                                "concurrent-link-hash-" + threadId,
                                "concurrent-code-hash-" + threadId,
                                CREATED_AT
                        )
                );
                return true;
            } catch (DataIntegrityViolationException exception) {
                return false;
            }
        };

        try {
            // when
            Future<Boolean> firstResult = executorService.submit(saveInvitation);
            Future<Boolean> secondResult = executorService.submit(saveInvitation);
            List<Boolean> results = List.of(
                    firstResult.get(),
                    secondResult.get()
            );

            // then
            assertThat(results).containsExactlyInAnyOrder(
                    true,
                    true
            );
            assertThat(countInvitations(workspace.getId())).isEqualTo(2);
        } finally {
            executorService.shutdownNow();
        }
    }

    private WorkspaceInvitation createInvitation(
            Long workspaceId,
            String linkTokenHash,
            String inviteCodeHash,
            Instant createdAt
    ) {
        return WorkspaceInvitation.create(
                workspaceId,
                linkTokenHash,
                inviteCodeHash,
                createdAt
        );
    }

    private Workspace saveAndFlush(Workspace workspace) {
        Workspace savedWorkspace = workspaceRepository.save(workspace);
        entityManager.flush();
        return savedWorkspace;
    }

    private WorkspaceInvitation saveAndFlush(WorkspaceInvitation invitation) {
        WorkspaceInvitation savedInvitation = workspaceInvitationRepository.save(invitation);
        entityManager.flush();
        return savedInvitation;
    }

    private WorkspaceInvitation saveAndReload(WorkspaceInvitation invitation) {
        WorkspaceInvitation savedInvitation = saveAndFlush(invitation);
        entityManager.clear();
        return workspaceInvitationRepository.findByLinkTokenHash(savedInvitation.getLinkTokenHash())
                .orElseThrow();
    }

    private void insertInvitation(
            Long workspaceId,
            String linkTokenHash,
            String inviteCodeHash,
            Instant expiresAt,
            Instant invalidatedAt,
            Instant createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_invitations (
                    workspace_id,
                    link_token_hash,
                    invite_code_hash,
                    expires_at,
                    invalidated_at,
                    created_at,
                    version
                ) VALUES (
                    :workspaceId,
                    :linkTokenHash,
                    :inviteCodeHash,
                    :expiresAt,
                    :invalidatedAt,
                    :createdAt,
                    0
                )
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "linkTokenHash",
                        linkTokenHash
                )
                .param(
                        "inviteCodeHash",
                        inviteCodeHash
                )
                .param(
                        "expiresAt",
                        toOffsetDateTime(expiresAt)
                )
                .param(
                        "invalidatedAt",
                        toOffsetDateTime(invalidatedAt)
                )
                .param(
                        "createdAt",
                        toOffsetDateTime(createdAt)
                )
                .update();
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(ZoneOffset.UTC);
    }

    private long countInvitations(Long workspaceId) {
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
}
