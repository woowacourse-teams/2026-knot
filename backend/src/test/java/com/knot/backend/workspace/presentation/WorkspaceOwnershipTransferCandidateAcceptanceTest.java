package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceOwnershipTransferCandidateAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final Instant EARLIER_JOINED_AT = Instant.parse("2026-10-01T00:00:30Z");
    private static final Instant LEFT_AT = Instant.parse("2026-10-01T00:02:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-10-01T00:03:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceOwnershipTransferCandidateAcceptanceTest(
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
    @DisplayName("활성 OWNER는 승계 후보를 memberId 오름차순 raw 배열로 조회하고 데이터는 변경되지 않는다")
    void findOwnershipTransferCandidates_success_filtersSortsAndDoesNotModifyData() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long lowerCandidateId = saveMember("이전 낮은 후보");
        long higherCandidateId = saveMember("높은 후보");
        long pastHistoryCandidateId = saveMember("재가입 후보");
        long departedMemberId = saveMember("탈퇴 멤버");
        long otherWorkspaceMemberId = saveMember("다른 워크스페이스 멤버");
        long deletedWorkspaceMemberId = saveMember("삭제 워크스페이스 멤버");
        long otherOwnerId = saveMember("다른 오너");
        long workspaceId = saveWorkspace("승계 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        long deletedWorkspaceId = saveWorkspace("삭제 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                higherCandidateId,
                "MEMBER",
                EARLIER_JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                lowerCandidateId,
                "MEMBER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                pastHistoryCandidateId,
                "MEMBER",
                EARLIER_JOINED_AT,
                LEFT_AT
        );
        saveWorkspaceMember(
                workspaceId,
                pastHistoryCandidateId,
                "MEMBER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                departedMemberId,
                "MEMBER",
                EARLIER_JOINED_AT,
                LEFT_AT
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                otherWorkspaceMemberId,
                "MEMBER",
                JOINED_AT
        );
        saveWorkspaceMember(
                deletedWorkspaceId,
                deletedWorkspaceMemberId,
                "MEMBER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                otherOwnerId,
                "OWNER",
                JOINED_AT
        );
        deleteWorkspace(deletedWorkspaceId);
        updateMemberNickname(
                lowerCandidateId,
                "현재 낮은 후보"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        List<String> memberSnapshot = memberSnapshots();
        List<String> workspaceSnapshot = workspaceSnapshots();
        List<String> workspaceMemberSnapshot = workspaceMemberSnapshots();

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        MvcResult mvcResult = result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].memberId").value(lowerCandidateId))
                .andExpect(jsonPath("$[0].nickname").value("현재 낮은 후보"))
                .andExpect(jsonPath("$[1].memberId").value(higherCandidateId))
                .andExpect(jsonPath("$[1].nickname").value("높은 후보"))
                .andExpect(jsonPath("$[2].memberId").value(pastHistoryCandidateId))
                .andExpect(jsonPath("$[2].nickname").value("재가입 후보"))
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andExpect(jsonPath("$[0].workspaceId").doesNotExist())
                .andExpect(jsonPath("$[0].joinedAt").doesNotExist())
                .andReturn();
        assertOnlyCandidateKeys(mvcResult);
        assertThat(memberSnapshots()).isEqualTo(memberSnapshot);
        assertThat(workspaceSnapshots()).isEqualTo(workspaceSnapshot);
        assertThat(workspaceMemberSnapshots()).isEqualTo(workspaceMemberSnapshot);
    }

    @Test
    @DisplayName("승계 후보가 없으면 활성 OWNER는 빈 raw 배열을 조회한다")
    void findOwnershipTransferCandidates_success_emptyCandidates() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("빈 후보 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET 승계 후보 조회는 CSRF 토큰이 없어도 성공한다")
    void findOwnershipTransferCandidates_success_withoutCsrfToken() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long candidateId = saveMember("member");
        long workspaceId = saveWorkspace("CSRF 없는 조회 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                candidateId,
                "MEMBER",
                JOINED_AT
        );
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberId").value(candidateId));
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스의 승계 후보 조회는 404를 반환한다")
    void findOwnershipTransferCandidates_failure_notFound() throws Exception {
        // given
        long ownerId = saveMember("owner");
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        Long.MAX_VALUE
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("워크스페이스를 찾을 수 없습니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "owner"
        );
    }

    @Test
    @DisplayName("삭제된 워크스페이스의 승계 후보 조회는 404를 반환한다")
    void findOwnershipTransferCandidates_failure_deletedWorkspace() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("삭제된 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        deleteWorkspace(workspaceId);
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("워크스페이스를 찾을 수 없습니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "삭제된 팀"
        );
    }

    @Test
    @DisplayName("활성 MEMBER의 승계 후보 조회는 403을 반환한다")
    void findOwnershipTransferCandidates_failure_activeMember() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("secret-member");
        long workspaceId = saveWorkspace("권한 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER",
                JOINED_AT
        );
        Cookie cookie = accessTokenCookie(memberId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("워크스페이스 OWNER 권한이 필요합니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "secret-member"
        );
    }

    @Test
    @DisplayName("비멤버의 승계 후보 조회는 403을 반환한다")
    void findOwnershipTransferCandidates_failure_nonMember() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long outsiderId = saveMember("outside-secret");
        long workspaceId = saveWorkspace("비멤버 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                JOINED_AT
        );
        Cookie cookie = accessTokenCookie(outsiderId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("워크스페이스 OWNER 권한이 필요합니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "outside-secret"
        );
    }

    @Test
    @DisplayName("탈퇴한 OWNER의 승계 후보 조회는 403을 반환한다")
    void findOwnershipTransferCandidates_failure_leftOwner() throws Exception {
        // given
        long leftOwnerId = saveMember("left-owner-secret");
        long activeOwnerId = saveMember("active-owner");
        long workspaceId = saveWorkspace("탈퇴 오너 팀");
        saveWorkspaceMember(
                workspaceId,
                leftOwnerId,
                "OWNER",
                JOINED_AT,
                LEFT_AT
        );
        saveWorkspaceMember(
                workspaceId,
                activeOwnerId,
                "OWNER",
                JOINED_AT
        );
        Cookie cookie = accessTokenCookie(leftOwnerId);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                ).cookie(cookie)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("워크스페이스 OWNER 권한이 필요합니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "left-owner-secret"
        );
    }

    @Test
    @DisplayName("인증되지 않은 승계 후보 조회는 401을 반환한다")
    void findOwnershipTransferCandidates_failure_unauthenticated() throws Exception {
        // given
        long workspaceId = saveWorkspace("무인증 팀");

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates",
                        workspaceId
                )
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다"));
        assertNoSensitiveInformation(
                result,
                "무인증 팀"
        );
    }

    @Test
    @DisplayName("워크스페이스 ID가 0이면 승계 후보 조회는 400을 반환한다")
    void findOwnershipTransferCandidates_failure_zeroWorkspaceId() throws Exception {
        // given
        long ownerId = saveMember("owner");
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc
                .perform(get("/api/v1/workspaces/0/ownership-transfer-candidates").cookie(cookie));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_ID"))
                .andExpect(jsonPath("$.message").value("워크스페이스 ID가 올바르지 않습니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "owner"
        );
    }

    @Test
    @DisplayName("워크스페이스 ID가 음수이면 승계 후보 조회는 400을 반환한다")
    void findOwnershipTransferCandidates_failure_negativeWorkspaceId() throws Exception {
        // given
        long ownerId = saveMember("owner");
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc
                .perform(get("/api/v1/workspaces/-1/ownership-transfer-candidates").cookie(cookie));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_ID"))
                .andExpect(jsonPath("$.message").value("워크스페이스 ID가 올바르지 않습니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "owner"
        );
    }

    @Test
    @DisplayName("워크스페이스 ID가 숫자가 아니면 승계 후보 조회는 400을 반환한다")
    void findOwnershipTransferCandidates_failure_invalidWorkspaceIdType() throws Exception {
        // given
        long ownerId = saveMember("owner");
        Cookie cookie = accessTokenCookie(ownerId);

        // when
        ResultActions result = mockMvc
                .perform(get("/api/v1/workspaces/not-a-number/ownership-transfer-candidates").cookie(cookie));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "owner"
        );
    }

    @Test
    @DisplayName("OpenAPI JSON에 승계 후보 조회 계약을 공개한다")
    void openApi_success_ownershipTransferCandidateContract() throws Exception {
        // given
        String openApiPath = "/v3/api-docs";
        String candidatesPath = "$.paths['/api/v1/workspaces/{workspaceId}/ownership-transfer-candidates'].get";
        String responseSchemaPath = candidatesPath + ".responses['200'].content['application/json'].schema";
        String candidateResponseRef = "#/components/schemas/WorkspaceOwnershipTransferCandidateResponse";
        String candidateResponsePropertiesPath = "$.components.schemas.WorkspaceOwnershipTransferCandidateResponse"
                + ".properties";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get(openApiPath));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(candidatesPath).exists())
                .andExpect(jsonPath(candidatesPath + ".summary").exists())
                .andExpect(jsonPath(candidatesPath + ".description").value(containsString("스냅샷")))
                .andExpect(jsonPath(candidatesPath + ".description").value(containsString("승계")))
                .andExpect(jsonPath(candidatesPath + ".security[*].accessTokenCookie").exists())
                .andExpect(jsonPath(candidatesPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')]").doesNotExist())
                .andExpect(jsonPath(responseSchemaPath + ".type").value("array"))
                .andExpect(jsonPath(responseSchemaPath + ".items['$ref']").value(candidateResponseRef))
                .andExpect(jsonPath(candidateResponsePropertiesPath + ".memberId").exists())
                .andExpect(jsonPath(candidateResponsePropertiesPath + ".nickname").exists())
                .andExpect(jsonPath(candidateResponsePropertiesPath + ".role").doesNotExist())
                .andExpect(jsonPath(candidateResponsePropertiesPath + ".workspaceId").doesNotExist())
                .andExpect(
                        jsonPath(candidatesPath + ".responses['400'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(candidatesPath + ".responses['401'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(candidatesPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(candidatesPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                );
    }

    private void assertOnlyCandidateKeys(MvcResult mvcResult) throws Exception {
        JsonNode responseBody = objectMapper.readTree(
                mvcResult.getResponse()
                        .getContentAsString()
        );
        for (JsonNode candidate : responseBody) {
            assertThat(candidate.propertyNames()).containsExactlyInAnyOrder(
                    "memberId",
                    "nickname"
            );
        }
    }

    private void assertNoSensitiveInformation(
            ResultActions result,
            String... sensitiveValues
    ) throws Exception {
        String responseBody = result.andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(responseBody).doesNotContain(
                "workspace_members",
                "workspaces.deleted_at",
                "workspace_members.left_at",
                "deleted_at",
                "left_at",
                "SELECT",
                "Exception"
        );
        assertThat(responseBody).doesNotContain(sensitiveValues);
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

    private void updateMemberNickname(
            long memberId,
            String nickname
    ) {
        jdbcClient.sql("""
                UPDATE members
                SET nickname = :nickname
                WHERE id = :memberId
                """)
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "nickname",
                        nickname
                )
                .update();
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

    private void deleteWorkspace(long workspaceId) {
        jdbcClient.sql("""
                UPDATE workspaces
                SET deleted_at = CAST(:deletedAt AS TIMESTAMPTZ)
                WHERE id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "deletedAt",
                        DELETED_AT.toString()
                )
                .update();
    }

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role,
            Instant joinedAt
    ) {
        saveWorkspaceMember(
                workspaceId,
                memberId,
                role,
                joinedAt,
                null
        );
    }

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role,
            Instant joinedAt,
            Instant leftAt
    ) {
        if (leftAt == null) {
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
                            role
                    )
                    .param(
                            "joinedAt",
                            joinedAt.toString()
                    )
                    .update();
            return;
        }
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, left_at)
                VALUES (:workspaceId, :memberId, :role, CAST(:joinedAt AS TIMESTAMPTZ), CAST(:leftAt AS TIMESTAMPTZ))
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
                        role
                )
                .param(
                        "joinedAt",
                        joinedAt.toString()
                )
                .param(
                        "leftAt",
                        leftAt.toString()
                )
                .update();
    }

    private List<String> memberSnapshots() {
        return jdbcClient.sql("""
                SELECT id::text || '|' || nickname || '|' || COALESCE(profile_image_url, '')
                FROM members
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }

    private List<String> workspaceSnapshots() {
        return jdbcClient.sql("""
                SELECT id::text || '|' || name || '|' || created_at::text || '|'
                    || COALESCE(deleted_at::text, '')
                FROM workspaces
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }

    private List<String> workspaceMemberSnapshots() {
        return jdbcClient.sql("""
                SELECT id::text || '|' || workspace_id::text || '|' || member_id::text || '|' || role || '|'
                    || joined_at::text || '|' || last_viewed::text || '|' || COALESCE(left_at::text, '')
                FROM workspace_members
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }
}
