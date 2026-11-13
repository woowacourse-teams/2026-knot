package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
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
class WorkspaceLastViewedAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceLastViewedAcceptanceTest(
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

    @DisplayName("마지막으로 본 워크스페이스를 바꾸면 목록 응답에 새 포인터가 반영된다")
    @Test
    void update_success_switchesLastViewedWorkspace() throws Exception {
        // given
        long memberId = saveMember("hyunsung");
        long firstWorkspaceId = saveWorkspace("첫 팀");
        long secondWorkspaceId = saveWorkspace("두 번째 팀");
        saveWorkspaceMember(
                firstWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                secondWorkspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        updateLastViewed(
                firstWorkspaceId,
                accessTokenCookie,
                csrfCredentials
        ).andExpect(status().isNoContent());

        // when
        ResultActions updateResult = updateLastViewed(
                secondWorkspaceId,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        updateResult.andExpect(status().isNoContent());
        assertThat(lastViewedWorkspaceIds(memberId)).containsExactly(secondWorkspaceId);
        mockMvc.perform(get("/api/v1/workspaces").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastViewedWorkspaceId").value(secondWorkspaceId));
    }

    @DisplayName("같은 워크스페이스를 반복 갱신해도 마지막 조회 상태 하나를 유지한다")
    @Test
    void update_success_idempotent() throws Exception {
        // given
        long memberId = saveMember("hyunsung");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions firstResult = updateLastViewed(
                workspaceId,
                accessTokenCookie,
                csrfCredentials
        );
        ResultActions secondResult = updateLastViewed(
                workspaceId,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        firstResult.andExpect(status().isNoContent());
        secondResult.andExpect(status().isNoContent());
        assertThat(lastViewedWorkspaceIds(memberId)).containsExactly(workspaceId);
    }

    @DisplayName("존재하지 않는 워크스페이스는 WORKSPACE_NOT_FOUND 404를 반환하고 기존 상태를 유지한다")
    @Test
    void update_failure_workspaceNotFound() throws Exception {
        // given
        long memberId = saveMember("hyunsung");
        long workspaceId = saveWorkspace("기존 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        markLastViewed(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = updateLastViewed(
                Long.MAX_VALUE,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        assertThat(lastViewedWorkspaceIds(memberId)).containsExactly(workspaceId);
    }

    @DisplayName("다른 멤버의 워크스페이스는 존재 여부를 숨긴 WORKSPACE_NOT_FOUND 404를 반환한다")
    @Test
    void update_failure_nonMemberWorkspace() throws Exception {
        // given
        long memberId = saveMember("hyunsung");
        long otherMemberId = saveMember("other-member");
        long workspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                workspaceId,
                otherMemberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = updateLastViewed(
                workspaceId,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        assertThat(lastViewedWorkspaceIds(memberId)).isEmpty();
    }

    @DisplayName("인증 없이 유효한 CSRF 토큰만 보내면 401을 반환한다")
    @Test
    void update_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                put("/api/v1/members/me/last-viewed-workspace").cookie(csrfCredentials.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":1}
                                """)
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @DisplayName("인증은 있지만 CSRF 토큰이 없으면 403을 반환한다")
    @Test
    void update_failure_missingCsrfToken() throws Exception {
        // given
        long memberId = saveMember("hyunsung");
        Cookie accessTokenCookie = accessTokenCookie(memberId);

        // when
        ResultActions result = mockMvc.perform(
                put("/api/v1/members/me/last-viewed-workspace").cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":1}
                                """)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions updateLastViewed(
            long workspaceId,
            Cookie accessTokenCookie,
            CsrfCredentials csrfCredentials
    ) throws Exception {
        return mockMvc.perform(
                put("/api/v1/members/me/last-viewed-workspace").cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":%d}
                                """.formatted(workspaceId))
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
            long memberId
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'MEMBER', CAST(:joinedAt AS TIMESTAMPTZ))
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
