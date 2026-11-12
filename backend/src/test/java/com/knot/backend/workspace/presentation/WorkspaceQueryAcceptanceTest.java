package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag("acceptance")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceQueryAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-08-29T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-29T00:01:00Z");
    private static final Instant RECENT_JOINED_AT = Instant.parse("2026-08-29T00:02:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final JdbcClient jdbcClient;

    WorkspaceQueryAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    @DisplayName("워크스페이스 멤버가 단건 조회하면 이름과 내 역할, 활성 멤버 수를 반환하고 데이터를 변경하지 않는다")
    void detail_success() throws Exception {
        // given
        long memberId = saveMember();
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        String token = accessToken(memberId);
        String workspaceSnapshot = workspaceSnapshot(workspaceId);
        String workspaceMemberSnapshot = workspaceMemberSnapshot(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        workspaceId
                ).cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Knot 팀"))
                .andExpect(jsonPath("$.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.activeMemberCount").value(1));
        assertThat(workspaceSnapshot(workspaceId)).isEqualTo(workspaceSnapshot);
        assertThat(
                workspaceMemberSnapshot(
                        workspaceId,
                        memberId
                )
        ).isEqualTo(workspaceMemberSnapshot);
    }

    @Test
    @DisplayName("워크스페이스 ID가 양수가 아니면 400을 반환한다")
    void detail_failure_invalidWorkspaceId() throws Exception {
        // given
        long memberId = saveMember();
        String token = accessToken(memberId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        0
                ).cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_ID"))
                .andExpect(jsonPath("$.message").value("워크스페이스 ID가 올바르지 않습니다"));
    }

    @Test
    @DisplayName("인증되지 않은 워크스페이스 단건 조회 요청은 401을 반환한다")
    void detail_failure_unauthenticated() throws Exception {
        // given
        long workspaceId = saveWorkspace("Knot 팀");

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        workspaceId
                )
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다"));
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 403을 반환한다")
    void detail_failure_accessDenied() throws Exception {
        // given
        long memberId = saveMember();
        long workspaceId = saveWorkspace("Knot 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId
        );
        String token = accessToken(memberId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        workspaceId
                ).cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("워크스페이스에 접근할 수 없습니다"));
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스는 404를 반환한다")
    void detail_failure_workspaceNotFound() throws Exception {
        // given
        long memberId = saveMember();
        String token = accessToken(memberId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        Long.MAX_VALUE
                ).cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("워크스페이스를 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("인증된 멤버의 OWNER·MEMBER 워크스페이스만 결정적 순서로 반환하고 데이터를 변경하지 않는다")
    void list_success_filtersSortsAndDoesNotModifyData() throws Exception {
        // given
        long memberId = saveMember();
        long otherMemberId = saveMember("other-member");
        long olderWorkspaceId = saveWorkspace("이전 팀");
        long tiedLowerWorkspaceId = saveWorkspace("최근 한 팀");
        long tiedHigherWorkspaceId = saveWorkspace("최근 두 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                olderWorkspaceId,
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        saveWorkspaceMember(
                tiedLowerWorkspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                RECENT_JOINED_AT
        );
        saveWorkspaceMember(
                tiedHigherWorkspaceId,
                memberId,
                WorkspaceMemberRole.OWNER,
                RECENT_JOINED_AT
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                otherMemberId,
                WorkspaceMemberRole.MEMBER,
                RECENT_JOINED_AT
        );
        markLastViewed(
                tiedLowerWorkspaceId,
                memberId
        );
        String token = accessToken(memberId);
        List<String> workspaceSnapshot = workspaceSnapshots();
        List<String> workspaceMemberSnapshot = workspaceMemberSnapshots();

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/workspaces").cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.lastViewedWorkspaceId").value(tiedLowerWorkspaceId))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.createdWorkspaceCount").doesNotExist())
                .andExpect(jsonPath("$.workspaces").isArray())
                .andExpect(jsonPath("$.workspaces.length()").value(3))
                .andExpect(jsonPath("$.workspaces[0].id").value(tiedHigherWorkspaceId))
                .andExpect(jsonPath("$.workspaces[0].name").value("최근 두 팀"))
                .andExpect(jsonPath("$.workspaces[0].length()").value(2))
                .andExpect(jsonPath("$.workspaces[0].role").doesNotExist())
                .andExpect(jsonPath("$.workspaces[0].joinedAt").doesNotExist())
                .andExpect(jsonPath("$.workspaces[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.workspaces[1].id").value(tiedLowerWorkspaceId))
                .andExpect(jsonPath("$.workspaces[1].name").value("최근 한 팀"))
                .andExpect(jsonPath("$.workspaces[2].id").value(olderWorkspaceId))
                .andExpect(jsonPath("$.workspaces[2].name").value("이전 팀"));
        assertThat(workspaceSnapshots()).isEqualTo(workspaceSnapshot);
        assertThat(workspaceMemberSnapshots()).isEqualTo(workspaceMemberSnapshot);
    }

    @Test
    @DisplayName("재가입한 워크스페이스는 새 참여 시각으로 한 번만 반환하고 과거 이력은 변경하지 않는다")
    void list_success_sortsRejoinedMembershipWithoutDuplicates() throws Exception {
        // given
        long memberId = saveMember();
        long rejoinedWorkspaceId = saveWorkspace("재가입 팀");
        long otherWorkspaceId = saveWorkspace("계속 참여한 팀");
        saveWorkspaceMember(
                rejoinedWorkspaceId,
                memberId,
                WorkspaceMemberRole.OWNER,
                JOINED_AT
        );
        markLastViewed(
                rejoinedWorkspaceId,
                memberId
        );
        markMembershipLeft(
                rejoinedWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                RECENT_JOINED_AT
        );
        saveWorkspaceMember(
                rejoinedWorkspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                RECENT_JOINED_AT.plusSeconds(1)
        );
        String token = accessToken(memberId);
        List<String> workspacesBefore = workspaceSnapshots();
        List<String> membershipsBefore = workspaceMemberSnapshots();

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/workspaces").cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.lastViewedWorkspaceId").value(nullValue()))
                .andExpect(jsonPath("$.workspaces.length()").value(2))
                .andExpect(jsonPath("$.workspaces[0].id").value(rejoinedWorkspaceId))
                .andExpect(jsonPath("$.workspaces[0].name").value("재가입 팀"))
                .andExpect(jsonPath("$.workspaces[1].id").value(otherWorkspaceId));
        assertThat(workspaceSnapshots()).isEqualTo(workspacesBefore);
        assertThat(workspaceMemberSnapshots()).isEqualTo(membershipsBefore);
    }

    @ParameterizedTest(name = "Workspace 삭제={0}, 다른 활성 Workspace 존재={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("탈퇴하거나 삭제된 마지막 대상은 목록에서 제외하고 선택 복구 없이 null을 반환한다")
    void list_success_excludesUnavailableLastViewedWithoutRepair(
            boolean deletedWorkspace,
            boolean hasActiveWorkspace
    ) throws Exception {
        // given
        long memberId = saveMember();
        long unavailableWorkspaceId = saveWorkspace("이전 마지막 팀");
        saveWorkspaceMember(
                unavailableWorkspaceId,
                memberId
        );
        markLastViewed(
                unavailableWorkspaceId,
                memberId
        );
        if (deletedWorkspace) {
            jdbcClient.sql("UPDATE workspaces SET deleted_at = CAST(:deletedAt AS TIMESTAMPTZ) WHERE id = :workspaceId")
                    .param(
                            "deletedAt",
                            RECENT_JOINED_AT.toString()
                    )
                    .param(
                            "workspaceId",
                            unavailableWorkspaceId
                    )
                    .update();
        } else {
            markMembershipLeft(
                    unavailableWorkspaceId,
                    memberId
            );
        }
        long activeWorkspaceId = saveWorkspace("남아 있는 팀");
        if (hasActiveWorkspace) {
            saveWorkspaceMember(
                    activeWorkspaceId,
                    memberId
            );
        }
        String token = accessToken(memberId);
        List<String> workspacesBefore = workspaceSnapshots();
        List<String> membershipsBefore = workspaceMemberSnapshots();

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/workspaces").cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.lastViewedWorkspaceId").value(nullValue()))
                .andExpect(jsonPath("$.workspaces.length()").value(hasActiveWorkspace ? 1 : 0));
        if (hasActiveWorkspace) {
            result.andExpect(jsonPath("$.workspaces[0].id").value(activeWorkspaceId));
        }
        assertThat(workspaceSnapshots()).isEqualTo(workspacesBefore);
        assertThat(workspaceMemberSnapshots()).isEqualTo(membershipsBefore);
    }

    @Test
    @DisplayName("소속 워크스페이스가 없는 인증된 멤버는 빈 목록을 반환받는다")
    void list_success_emptyMemberships() throws Exception {
        // given
        long memberId = saveMember();
        String token = accessToken(memberId);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/workspaces").cookie(
                        new Cookie(
                                JWT_COOKIE_NAME,
                                token
                        )
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.lastViewedWorkspaceId").value(nullValue()))
                .andExpect(jsonPath("$.workspaces").isArray())
                .andExpect(jsonPath("$.workspaces").isEmpty());
    }

    @Test
    @DisplayName("인증되지 않은 워크스페이스 목록 조회 요청은 401을 반환한다")
    void list_failure_unauthenticated() throws Exception {
        // given

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/workspaces"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다"));
    }

    private long saveMember() {
        return saveMember("hyunsung");
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
            long memberId
    ) {
        saveWorkspaceMember(
                workspaceId,
                memberId,
                WorkspaceMemberRole.MEMBER,
                JOINED_AT
        );
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

    private List<String> workspaceSnapshots() {
        return jdbcClient.sql("""
                SELECT row_to_json(w)::text
                FROM workspaces w
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }

    private List<String> workspaceMemberSnapshots() {
        return jdbcClient.sql("""
                SELECT row_to_json(wm)::text
                FROM workspace_members wm
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }

    private String workspaceSnapshot(long workspaceId) {
        return jdbcClient.sql("""
                SELECT name || '|' || created_at::text
                FROM workspaces
                WHERE id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .single();
    }

    private String workspaceMemberSnapshot(
            long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT role || '|' || joined_at::text || '|' || last_viewed::text
                FROM workspace_members
                WHERE workspace_id = :workspaceId AND member_id = :memberId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(String.class)
                .single();
    }

    private void markLastViewed(
            long workspaceId,
            long memberId
    ) {
        jdbcClient.sql("""
                UPDATE workspace_members
                SET last_viewed = TRUE
                WHERE workspace_id = :workspaceId AND member_id = :memberId
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
                        RECENT_JOINED_AT.toString()
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

    private String accessToken(long memberId) {
        return authTokenProvider.issue(
                AuthenticatedMember.of(
                        memberId,
                        "hyunsung",
                        null
                )
        );
    }

}
