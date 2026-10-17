package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceServiceIntegrationTest {
    private final WorkspaceService workspaceService;
    private final JdbcClient jdbcClient;

    WorkspaceServiceIntegrationTest(
            WorkspaceService workspaceService,
            JdbcClient jdbcClient
    ) {
        this.workspaceService = workspaceService;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    @DisplayName("워크스페이스를 생성하면 워크스페이스와 OWNER 멤버십을 함께 저장한다")
    void create_success() {
        // given
        long memberId = saveMember("octocat");

        // when
        long workspaceId = workspaceService.create(
                memberId,
                "Knot 팀"
        );

        // then
        assertThat(workspaceId).isPositive();
        assertThat(workspaceName(workspaceId)).isEqualTo("Knot 팀");
        assertThat(workspaceMemberRole(workspaceId)).isEqualTo("OWNER");
        assertThat(workspaceMemberId(workspaceId)).isEqualTo(memberId);
        assertThat(workspaceCreatedAt(workspaceId)).isEqualTo(workspaceJoinedAt(workspaceId));
    }

    @Test
    @DisplayName("같은 member가 같은 이름으로 다시 생성하면 별도 워크스페이스를 저장한다")
    void create_success_duplicateWorkspaceName() {
        // given
        long memberId = saveMember("octocat");
        long firstWorkspaceId = workspaceService.create(
                memberId,
                "Knot 팀"
        );

        // when
        long secondWorkspaceId = workspaceService.create(
                memberId,
                "Knot 팀"
        );

        // then
        assertThat(secondWorkspaceId).isNotEqualTo(firstWorkspaceId);
        assertThat(count("workspaces")).isEqualTo(2);
        assertThat(count("workspace_members")).isEqualTo(2);
    }

    @Test
    @DisplayName("멤버십 저장이 실패하면 먼저 저장한 워크스페이스도 rollback한다")
    void create_failure_membershipSaveRollsBackWorkspace() {
        // given

        // when
        Throwable thrown = catchThrowable(
                () -> workspaceService.create(
                        Long.MAX_VALUE,
                        "Knot 팀"
                )
        );

        // then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("workspaces")).isZero();
        assertThat(count("workspace_members")).isZero();
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

    private String workspaceName(long workspaceId) {
        return jdbcClient.sql("SELECT name FROM workspaces WHERE id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .single();
    }

    private String workspaceMemberRole(long workspaceId) {
        return jdbcClient.sql("SELECT role FROM workspace_members WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .single();
    }

    private long workspaceMemberId(long workspaceId) {
        return jdbcClient.sql("SELECT member_id FROM workspace_members WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private Instant workspaceCreatedAt(long workspaceId) {
        return jdbcClient.sql("SELECT created_at FROM workspaces WHERE id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Instant.class)
                .single();
    }

    private Instant workspaceJoinedAt(long workspaceId) {
        return jdbcClient.sql("SELECT joined_at FROM workspace_members WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Instant.class)
                .single();
    }

    private int count(String tableName) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName)
                .query(Integer.class)
                .single();
    }
}
