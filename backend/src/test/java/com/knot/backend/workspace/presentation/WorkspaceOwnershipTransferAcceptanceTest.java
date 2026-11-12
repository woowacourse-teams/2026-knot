package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
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
class WorkspaceOwnershipTransferAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final Instant LEFT_AT = Instant.parse("2026-10-01T00:02:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-10-01T00:03:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceOwnershipTransferAcceptanceTest(
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
    @DisplayName("활성 OWNER가 활성 MEMBER에게 권한을 승계하면 204와 빈 본문을 반환하고 요청자는 탈퇴한다")
    void transferOwnership_success() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("승계 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        markLastViewed(
                workspaceId,
                ownerId
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        Cookie ownerCookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                ownerCookie,
                csrf
        );

        // then
        result.andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(
                activeRole(
                        workspaceId,
                        successorId
                )
        ).isEqualTo("OWNER");
        assertThat(
                activeRole(
                        workspaceId,
                        ownerId
                )
        ).isNull();
        assertThat(
                leftWorkspaceMemberSnapshot(
                        workspaceId,
                        ownerId
                )
        ).contains("|OWNER|")
                .endsWith("|false");
        mockMvc.perform(get("/api/v1/workspaces").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces").isEmpty());
        mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}",
                        workspaceId
                ).cookie(accessTokenCookie(successorId))
        )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("응답 유실 뒤 기존 OWNER가 같은 승계를 재요청하면 403이며 데이터는 변경되지 않는다")
    void transferOwnership_failure_retryAfterSuccess() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("재시도 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        Cookie ownerCookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();
        transferOwnership(
                workspaceId,
                successorId,
                ownerCookie,
                csrf
        ).andExpect(status().isNoContent());
        List<String> memberSnapshot = memberSnapshots();
        List<String> workspaceSnapshot = workspaceSnapshots();
        List<String> workspaceMemberSnapshot = workspaceMemberSnapshots();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                ownerCookie,
                csrf
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        assertNoSensitiveInformation(
                result,
                ownerCookie.getValue()
        );
        assertThat(memberSnapshots()).isEqualTo(memberSnapshot);
        assertThat(workspaceSnapshots()).isEqualTo(workspaceSnapshot);
        assertThat(workspaceMemberSnapshots()).isEqualTo(workspaceMemberSnapshot);
    }

    @Test
    @DisplayName("인증 없이 유효한 CSRF 토큰만 보내면 승계는 401이다")
    void transferOwnership_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/ownership-transfers").cookie(csrf.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(2L))
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 승계는 403이다")
    void transferOwnership_failure_missingCsrf() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/ownership-transfers").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(2L))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("CSRF 토큰이 유효하지 않으면 승계는 403이다")
    void transferOwnership_failure_invalidCsrf() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/ownership-transfers").cookie(
                        cookie,
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                "invalid-token"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(2L))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("요청 본문이 JSON 형식이 아니면 승계는 400이다")
    void transferOwnership_failure_malformedJson() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                "{",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("요청 본문이 비어 있으면 승계는 400이다")
    void transferOwnership_failure_emptyJson() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                "",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("워크스페이스 ID가 숫자가 아니면 승계는 400이다")
    void transferOwnership_failure_invalidWorkspaceIdType() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "not-a-number",
                requestBody(2L),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workspaceId"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("successorMemberId가 없으면 승계는 field error가 있는 400이다")
    void transferOwnership_failure_missingSuccessorMemberId() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                "{}",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("successorMemberId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("승계할 멤버 ID는 필수입니다"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("successorMemberId가 null이면 승계는 field error가 있는 400이다")
    void transferOwnership_failure_nullSuccessorMemberId() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                "{\"successorMemberId\":null}",
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("successorMemberId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("승계할 멤버 ID는 필수입니다"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("successorMemberId가 0이면 승계는 field error가 있는 400이다")
    void transferOwnership_failure_zeroSuccessorMemberId() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                requestBody(0L),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("successorMemberId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("승계할 멤버 ID는 양수여야 합니다"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("successorMemberId가 음수이면 승계는 field error가 있는 400이다")
    void transferOwnership_failure_negativeSuccessorMemberId() throws Exception {
        // given
        Cookie cookie = accessTokenCookie(saveMember("owner"));
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = postOwnershipTransfer(
                "1",
                requestBody(-1L),
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("successorMemberId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("승계할 멤버 ID는 양수여야 합니다"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("본인을 승계 대상으로 지정하면 승계는 400이다")
    void transferOwnership_failure_selfTarget() throws Exception {
        // given
        long ownerId = saveMember("self-secret");
        long workspaceId = saveWorkspace("본인 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                ownerId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_OWNERSHIP_TRANSFER_TARGET"))
                .andExpect(jsonPath("$.message").value("본인에게 OWNER 권한을 승계할 수 없습니다"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "self-secret"
        );
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스 승계는 404다")
    void transferOwnership_failure_workspaceNotFound() throws Exception {
        // given
        long ownerId = saveMember("owner-secret");
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                Long.MAX_VALUE,
                ownerId + 1,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "owner-secret"
        );
    }

    @Test
    @DisplayName("삭제된 워크스페이스 승계는 404다")
    void transferOwnership_failure_deletedWorkspace() throws Exception {
        // given
        long ownerId = saveMember("owner-secret");
        long successorId = saveMember("successor-secret");
        long workspaceId = saveWorkspace("삭제 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        deleteWorkspace(workspaceId);
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "삭제 팀"
        );
    }

    @Test
    @DisplayName("활성 MEMBER가 승계를 요청하면 403이다")
    void transferOwnership_failure_actorMember() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member-secret");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("권한 팀");
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
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "member-secret"
        );
    }

    @Test
    @DisplayName("탈퇴한 OWNER가 승계를 요청하면 403이다")
    void transferOwnership_failure_actorInactive() throws Exception {
        // given
        long ownerId = saveMember("left-owner-secret");
        long activeOwnerId = saveMember("active-owner");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("탈퇴 요청자 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER",
                LEFT_AT
        );
        saveWorkspaceMember(
                workspaceId,
                activeOwnerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "left-owner-secret"
        );
    }

    @Test
    @DisplayName("비멤버가 승계를 요청하면 403이다")
    void transferOwnership_failure_actorOutsider() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long outsiderId = saveMember("outsider-secret");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace("외부자 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(outsiderId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_REQUIRED"));
        assertNoSensitiveInformation(
                result,
                cookie.getValue(),
                "outsider-secret"
        );
    }

    @Test
    @DisplayName("존재하지 않는 멤버를 승계 대상으로 지정하면 409다")
    void transferOwnership_failure_successorNotFound() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace("대상 없음 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                Long.MAX_VALUE,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT"));
        assertNoSensitiveInformation(result);
    }

    @Test
    @DisplayName("다른 워크스페이스 멤버를 승계 대상으로 지정하면 409다")
    void transferOwnership_failure_successorDifferentWorkspace() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("other-target-secret");
        long workspaceId = saveWorkspace("원본 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                successorId,
                "MEMBER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT"));
        assertNoSensitiveInformation(
                result,
                "other-target-secret"
        );
    }

    @Test
    @DisplayName("탈퇴한 멤버를 승계 대상으로 지정하면 409다")
    void transferOwnership_failure_successorLeft() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("left-target-secret");
        long workspaceId = saveWorkspace("탈퇴 대상 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "MEMBER",
                LEFT_AT
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT"));
        assertNoSensitiveInformation(
                result,
                "left-target-secret"
        );
    }

    @Test
    @DisplayName("이미 OWNER인 멤버를 승계 대상으로 지정하면 409다")
    void transferOwnership_failure_successorAlreadyOwner() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("owner-target-secret");
        long workspaceId = saveWorkspace("기존 오너 팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        saveWorkspaceMember(
                workspaceId,
                successorId,
                "OWNER"
        );
        Cookie cookie = accessTokenCookie(ownerId);
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = transferOwnership(
                workspaceId,
                successorId,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNERSHIP_TRANSFER_TARGET_CONFLICT"));
        assertNoSensitiveInformation(
                result,
                "owner-target-secret"
        );
    }

    @Test
    @DisplayName("OpenAPI JSON에 OWNER 승계 계약을 공개한다")
    void openApi_success_ownershipTransferContract() throws Exception {
        // given
        String openApiPath = "/v3/api-docs";
        String transferPath = "$.paths['/api/v1/workspaces/{workspaceId}/ownership-transfers'].post";
        String requestSchemaPath = "$.components.schemas.WorkspaceOwnershipTransferRequest";
        String successorSchemaPath = requestSchemaPath + ".properties.successorMemberId";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get(openApiPath));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(transferPath).exists())
                .andExpect(jsonPath(transferPath + ".summary").value("워크스페이스 OWNER 승계와 탈퇴"))
                .andExpect(jsonPath(transferPath + ".security[*].accessTokenCookie").exists())
                .andExpect(jsonPath(transferPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')]").exists())
                .andExpect(jsonPath(transferPath + ".responses['204']").exists())
                .andExpect(jsonPath(transferPath + ".responses['204'].content").doesNotExist())
                .andExpect(
                        jsonPath(transferPath + ".requestBody.content['application/json'].schema['$ref']")
                                .value("#/components/schemas/WorkspaceOwnershipTransferRequest")
                )
                .andExpect(jsonPath(requestSchemaPath + ".required").value(hasItem("successorMemberId")))
                .andExpect(jsonPath(successorSchemaPath + ".minimum").value(1))
                .andExpect(
                        jsonPath(transferPath + ".responses['400'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(transferPath + ".responses['401'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(transferPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(transferPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(transferPath + ".responses['409'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                );
    }

    private ResultActions transferOwnership(
            long workspaceId,
            long successorMemberId,
            Cookie cookie,
            CsrfCredentials csrf
    ) throws Exception {
        return postOwnershipTransfer(
                Long.toString(workspaceId),
                requestBody(successorMemberId),
                cookie,
                csrf
        );
    }

    private ResultActions postOwnershipTransfer(
            String workspaceId,
            String requestBody,
            Cookie cookie,
            CsrfCredentials csrf
    ) throws Exception {
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/ownership-transfers").cookie(
                        cookie,
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
        );
    }

    private String requestBody(long successorMemberId) {
        return """
                {"successorMemberId":%d}
                """.formatted(successorMemberId);
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
        if (sensitiveValues.length > 0) {
            assertThat(responseBody).doesNotContain(sensitiveValues);
        }
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
            String role
    ) {
        saveWorkspaceMember(
                workspaceId,
                memberId,
                role,
                null
        );
    }

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId,
            String role,
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
                            JOINED_AT.toString()
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
                        JOINED_AT.toString()
                )
                .param(
                        "leftAt",
                        leftAt.toString()
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
                WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
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

    private String activeRole(
            long workspaceId,
            long memberId
    ) {
        List<String> roles = jdbcClient.sql("""
                SELECT role
                FROM workspace_members
                WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NULL
                ORDER BY id
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
                .list();
        if (roles.isEmpty()) {
            return null;
        }
        return roles.getFirst();
    }

    private String leftWorkspaceMemberSnapshot(
            long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT id::text || '|' || workspace_id::text || '|' || member_id::text || '|' || role || '|'
                    || joined_at::text || '|' || COALESCE(left_at::text, '') || '|' || last_viewed::text
                FROM workspace_members
                WHERE workspace_id = :workspaceId AND member_id = :memberId AND left_at IS NOT NULL
                ORDER BY id DESC
                LIMIT 1
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
                    || joined_at::text || '|' || COALESCE(left_at::text, '') || '|' || last_viewed::text
                FROM workspace_members
                ORDER BY id
                """)
                .query(String.class)
                .list();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
