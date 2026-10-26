package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceAcceptanceTest(
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
    @DisplayName("인증된 사용자가 워크스페이스를 생성하면 생성자 OWNER 멤버십과 ID를 반환한다")
    void create_success() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Knot 팀"}
                                """)
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
        JsonNode responseBody = objectMapper.readTree(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        long workspaceId = responseBody.get("id")
                .asLong();
        assertThat(responseBody.size()).isEqualTo(1);
        assertThat(singleWorkspaceName(workspaceId)).isEqualTo("Knot 팀");
        assertThat(singleWorkspaceMemberWorkspaceId()).isEqualTo(workspaceId);
        assertThat(count("workspaces")).isEqualTo(1);
        assertThat(count("workspace_members")).isEqualTo(1);
        assertThat(singleWorkspaceMemberRole()).isEqualTo("OWNER");
        assertThat(singleWorkspaceMemberId()).isEqualTo(memberId);
        assertThat(singleWorkspaceMemberLastViewed()).isFalse();
    }

    @Test
    @DisplayName("숫자가 든 이름으로 워크스페이스를 생성한다")
    void create_success_nameContainingDigits() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"우테코 8기"}
                                """)
        );

        // then
        result.andExpect(status().isCreated());
        long workspaceId = objectMapper.readTree(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        )
                .get("id")
                .asLong();
        assertThat(singleWorkspaceName(workspaceId)).isEqualTo("우테코 8기");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Knot!", "２０２６"})
    @DisplayName("워크스페이스 이름 규칙을 위반하면 INVALID_WORKSPACE_NAME 400 응답을 반환한다")
    void create_failure_invalidWorkspaceName(String invalidName) throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(invalidName))
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_NAME"));
        assertThat(count("workspaces")).isZero();
        assertThat(count("workspace_members")).isZero();
    }

    @Test
    @DisplayName("인증되지 않은 워크스페이스 생성 요청은 401로 거부한다")
    void create_failure_unauthorizedWithCsrfToken() throws Exception {
        // given
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").cookie(csrfCredentials.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Knot 팀"}
                                """)
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다"));
        assertThat(count("workspaces")).isZero();
        assertThat(count("workspace_members")).isZero();
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 워크스페이스 생성 요청은 403으로 거부한다")
    void create_failure_missingCsrfToken() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Knot 팀"}
                                """)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("요청 권한이 없습니다"));
        assertThat(count("workspaces")).isZero();
        assertThat(count("workspace_members")).isZero();
    }

    @Test
    @DisplayName("JWT와 CSRF 토큰이 모두 없는 워크스페이스 생성 요청은 403으로 거부한다")
    void create_failure_missingAuthenticationAndCsrfToken() throws Exception {
        // given
        String requestBody = """
                {"name":"Knot 팀"}
                """;

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("요청 권한이 없습니다"));
        assertThat(count("workspaces")).isZero();
        assertThat(count("workspace_members")).isZero();
    }

    private Cookie accessTokenCookie(long memberId) {
        String token = authTokenProvider.issue(
                AuthenticatedMember.of(
                        memberId,
                        "octocat",
                        null
                )
        );
        return new Cookie(
                JWT_COOKIE_NAME,
                token
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

    private String singleWorkspaceMemberRole() {
        return jdbcClient.sql("SELECT role FROM workspace_members")
                .query(String.class)
                .single();
    }

    private String singleWorkspaceName(long workspaceId) {
        return jdbcClient.sql("SELECT name FROM workspaces WHERE id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .single();
    }

    private long singleWorkspaceMemberWorkspaceId() {
        return jdbcClient.sql("SELECT workspace_id FROM workspace_members")
                .query(Long.class)
                .single();
    }

    private long singleWorkspaceMemberId() {
        return jdbcClient.sql("SELECT member_id FROM workspace_members")
                .query(Long.class)
                .single();
    }

    private boolean singleWorkspaceMemberLastViewed() {
        return jdbcClient.sql("SELECT last_viewed FROM workspace_members")
                .query(Boolean.class)
                .single();
    }

    private int count(String tableName) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName)
                .query(Integer.class)
                .single();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
