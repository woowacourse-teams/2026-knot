package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceLeaveAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceLeaveAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    @DisplayName("멤버 탈퇴는 204를 반환하고 목록·상세 접근과 마지막 조회 상태를 제거한다")
    void leave_success_removesAccessAndLastViewed() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        markLastViewed(
                workspaceId,
                memberId
        );
        Cookie cookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(lastViewedWorkspaceIds(memberId)).isEmpty();
        mockMvc.perform(get("/api/v1/workspaces").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces").isEmpty());
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("활성 워크스페이스에 이미 탈퇴한 멤버의 재요청은 204다")
    void leave_success_retry() throws Exception {
        // given
        long memberId = saveMember("member");
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();
        leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        ).andExpect(status().isNoContent());

        // when
        ResultActions result = leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("다른 활성 멤버가 있는 OWNER의 일반 탈퇴는 409다")
    void leave_failure_ownerTransferRequired() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_TRANSFER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("OWNER 권한을 승계하거나 워크스페이스를 삭제해야 합니다"));
    }

    @Test
    @DisplayName("마지막 멤버 탈퇴 후 삭제된 워크스페이스에 재요청하면 404다")
    void leave_failure_retryAfterLastMemberDeletedWorkspace() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();
        leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        ).andExpect(status().isNoContent());

        // when
        ResultActions result = leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("참여 이력이 없는 요청자는 403을 받는다")
    void leave_failure_noMembershipHistory() throws Exception {
        // given
        long memberId = saveMember("outsider");
        long workspaceId = saveWorkspace("팀");
        Cookie cookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                Long.toString(workspaceId),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스 탈퇴는 404다")
    void leave_failure_notFound() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("member"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                Long.toString(Long.MAX_VALUE),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("워크스페이스 ID가 양수가 아니면 400이다")
    void leave_failure_nonPositiveId() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("member"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                "0",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_ID"));
    }

    @Test
    @DisplayName("워크스페이스 ID 형식이 잘못되면 내부 오류 없는 400이다")
    void leave_failure_invalidIdFormat() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("member"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = leave(
                "invalid",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("인증 없이 CSRF 토큰만 보내면 탈퇴는 401이다")
    void leave_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                delete("/api/v1/workspaces/1/members/me").cookie(csrf.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 탈퇴는 403이다")
    void leave_failure_missingCsrf() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("member"));

        // when
        ResultActions result = mockMvc.perform(delete("/api/v1/workspaces/1/members/me").cookie(cookie));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions leave(
            String workspaceId,
            Cookie cookie,
            CsrfCredentials csrf
    ) throws Exception {
        return mockMvc.perform(
                delete("/api/v1/workspaces/" + workspaceId + "/members/me").cookie(
                        cookie,
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        );
    }

    private CsrfCredentials csrfCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(CSRF_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        JsonNode responseBody = objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
        return new CsrfCredentials(
                cookie,
                responseBody.get("token")
                        .asText()
        );
    }

    private Cookie accessTokenCookie(long memberId) {
        return new Cookie(
                JWT_COOKIE_NAME,
                authTokenProvider.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "hyunsung",
                                null
                        )
                )
        );
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
            String role
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
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .param(
                        "role",
                        role
                )
                .update();
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

    private List<Long> lastViewedWorkspaceIds(long memberId) {
        return jdbcClient.sql("""
                SELECT workspace_id
                FROM workspace_members
                WHERE member_id = :memberId AND last_viewed
                ORDER BY workspace_id
                """)
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .list();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
