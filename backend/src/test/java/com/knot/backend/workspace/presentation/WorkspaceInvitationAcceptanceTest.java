package com.knot.backend.workspace.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.member.domain.Member;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.WorkspaceInvitationSecretHasher;
import com.knot.backend.workspace.application.WorkspaceInvitationService;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceInvitation;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ExtendWith(OutputCaptureExtension.class)
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceInvitationAcceptanceTest {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final AuthTokenProvider authTokenProvider;
    private final WorkspaceInvitationSecretHasher workspaceInvitationSecretHasher;
    private final MemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final JdbcClient jdbcClient;
    private final WorkspaceInvitationService invitationService;

    WorkspaceInvitationAcceptanceTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            AuthTokenProvider authTokenProvider,
            WorkspaceInvitationSecretHasher workspaceInvitationSecretHasher,
            MemberRepository memberRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            JdbcClient jdbcClient,
            WorkspaceInvitationService invitationService
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.authTokenProvider = authTokenProvider;
        this.workspaceInvitationSecretHasher = workspaceInvitationSecretHasher;
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.jdbcClient = jdbcClient;
        this.invitationService = invitationService;
    }

    @DisplayName("인증 없이 6자리 코드를 조회하면 주변 공백과 소문자를 허용하고 대상 워크스페이스만 반환한다")
    @Test
    void preview_success_codeWithoutAuthentication() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        JsonNode issued = issue(fixture);
        String code = issued.get("code")
                .asText()
                .toLowerCase(Locale.ROOT) + " ";

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        code
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.workspaceName").value("초대 테스트 팀"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.linkToken").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @DisplayName("context path가 있어도 초대 미리보기 응답에 no-store를 적용한다")
    @Test
    void preview_success_noStoreWithContextPath() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        String code = issue(fixture).get("code")
                .asText();

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/knot/api/v1/invitations/{tokenOrCode}",
                        code
                ).contextPath("/knot")
                        .with(request -> {
                            request.setRemoteAddr(uniqueValue("remote"));
                            return request;
                        })
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.workspaceName").value("초대 테스트 팀"));
    }

    @DisplayName("링크 토큰은 원문 대소문자가 일치할 때만 미리보기에 성공하고 코드 조회 제한을 소비하지 않는다")
    @Test
    void preview_success_exactLinkTokenWithoutRateLimitConsumption() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        JsonNode issued = issue(fixture);
        String remoteAddress = uniqueValue("remote");
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                29
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        issued.get("linkToken")
                                .asText()
                ).with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.workspaceName").value("초대 테스트 팀"));
        mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        "ABC1O0"
                ).with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
        )
                .andExpect(status().isNotFound());
    }

    @DisplayName("링크 토큰의 대소문자가 바뀌면 원인을 구분하지 않는 404를 반환한다")
    @Test
    void preview_failure_caseChangedLinkToken() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        String linkToken = issue(fixture).get("linkToken")
                .asText();
        String changedLinkToken = changeFirstLetterCase(linkToken);

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        changedLinkToken
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("내부 공백, 하이픈, 금지 문자가 있는 6자리 값은 동일한 404 공개 계약을 따른다")
    @Test
    void preview_failure_invalidCodeLooksLikeNotFound() throws Exception {
        // given
        List<String> invalidCodes = List.of(
                "AB CD1",
                "AB-CD1",
                "ABC1O0"
        );

        // when
        for (String invalidCode : invalidCodes) {
            ResultActions result = mockMvc.perform(
                    get(
                            "/api/v1/invitations/{tokenOrCode}",
                            invalidCode
                    ).with(request -> {
                        request.setRemoteAddr(uniqueValue("remote"));
                        return request;
                    })
            );

            // then
            result.andExpect(status().isNotFound())
                    .andExpect(
                            header().string(
                                    HttpHeaders.CACHE_CONTROL,
                                    containsString("no-store")
                            )
                    )
                    .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
        }
    }

    @DisplayName("존재하지 않는 정상 형식 코드는 원인을 구분하지 않는 404를 반환한다")
    @Test
    void preview_failure_missingCode() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        String code = issue(fixture).get("code")
                .asText();
        deleteInvitations(fixture.workspaceId());

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        code
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("링크 토큰에는 코드의 주변 공백 정규화를 적용하지 않는다")
    @Test
    void preview_failure_linkTokenWithSurroundingWhitespace() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        String linkToken = issue(fixture).get("linkToken")
                .asText();

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        " " + linkToken + " "
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("만료된 초대 코드는 원인을 구분하지 않는 404를 반환한다")
    @Test
    void preview_failure_expiredInvitation() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        String code = issue(fixture).get("code")
                .asText();
        expireInvitation(fixture.workspaceId());

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        code
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("과거 재발급으로 무효화된 초대 코드는 원인을 구분하지 않는 404를 반환한다")
    @Test
    void preview_failure_invalidated() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        JsonNode firstIssued = issue(fixture);
        jdbcClient.sql("UPDATE workspace_invitations SET invalidated_at = created_at WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        fixture.workspaceId()
                )
                .update();

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        firstIssued.get("code")
                                .asText()
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("같은 IP의 코드 후보 31번째 미리보기 요청은 429와 Retry-After를 반환한다")
    @Test
    void preview_failure_rateLimitedCodeCandidate() throws Exception {
        // given
        String remoteAddress = uniqueValue("remote");
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                30
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        "ABC1O0"
                ).header(
                        HttpHeaders.ORIGIN,
                        "https://knoted.kr"
                )
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
        );

        // then
        result.andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                HttpHeaders.RETRY_AFTER + ", X-Request-ID"
                        )
                )
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_RATE_LIMIT_EXCEEDED"));
    }

    @DisplayName("서로 다른 IP의 코드 후보 조회 횟수는 독립적으로 계산한다")
    @Test
    void preview_success_separatesRemoteAddressRateLimit() throws Exception {
        // given
        String saturatedRemoteAddress = uniqueValue("remote");
        consumePreviewAttempts(
                "ABC1O0",
                saturatedRemoteAddress,
                30
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        "ABC1O0"
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("초대 미리보기는 조회 전후 초대 Row 상태를 변경하지 않는다")
    @Test
    void preview_success_preservesInvitationRows() throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        JsonNode issued = issue(fixture);
        List<String> invitationSnapshot = invitationSnapshot(fixture.workspaceId());

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        issued.get("code")
                                .asText()
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        );

        // then
        result.andExpect(status().isOk());
        assertThat(invitationSnapshot(fixture.workspaceId())).isEqualTo(invitationSnapshot);
    }

    @DisplayName("초대 미리보기는 코드와 링크 토큰 원문을 로그에 남기지 않는다")
    @Test
    void preview_success_doesNotLogRawCredential(CapturedOutput output) throws Exception {
        // given
        WorkspaceFixture fixture = createWorkspaceFixture(true);
        JsonNode issued = issue(fixture);
        String code = issued.get("code")
                .asText();
        String linkToken = issued.get("linkToken")
                .asText();

        // when
        mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        code
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        )
                .andExpect(status().isOk());
        mockMvc.perform(
                get(
                        "/api/v1/invitations/{tokenOrCode}",
                        linkToken
                ).with(request -> {
                    request.setRemoteAddr(uniqueValue("remote"));
                    return request;
                })
        )
                .andExpect(status().isOk());

        // then
        assertThat(output.getAll()).doesNotContain(
                code,
                linkToken
        );
    }

    private WorkspaceFixture createWorkspaceFixture(boolean memberOfWorkspace) {
        Instant now = Instant.now()
                .minusSeconds(1);
        Member member = memberRepository.save(
                Member.create(
                        uniqueValue("member").replaceAll(
                                "[0-9]",
                                "g"
                        ),
                        null
                )
        );
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        "초대 테스트 팀",
                        now
                )
        );
        if (memberOfWorkspace) {
            workspaceMemberRepository.save(
                    WorkspaceMember.create(
                            workspace.getId(),
                            member.getId(),
                            WorkspaceMemberRole.MEMBER,
                            now
                    )
            );
        }
        return new WorkspaceFixture(
                workspace.getId(),
                AuthenticatedMember.of(
                        member.getId(),
                        member.getNickname(),
                        member.getProfileImageUrl()
                )
        );
    }

    private JsonNode issue(WorkspaceFixture fixture) {
        return objectMapper.valueToTree(
                invitationService.issue(
                        fixture.workspaceId(),
                        fixture.member()
                                .getMemberId()
                )
        );
    }

    private void consumePreviewAttempts(
            String tokenOrCode,
            String remoteAddress,
            int count
    ) throws Exception {
        for (int attempt = 0; attempt < count; attempt++) {
            mockMvc.perform(
                    get(
                            "/api/v1/invitations/{tokenOrCode}",
                            tokenOrCode
                    ).with(request -> {
                        request.setRemoteAddr(remoteAddress);
                        return request;
                    })
            )
                    .andExpect(status().isNotFound());
        }
    }

    private void expireInvitation(Long workspaceId) {
        Instant expiresAt = Instant.now()
                .minusSeconds(1);
        jdbcClient.sql("""
                UPDATE workspace_invitations
                SET created_at = :createdAt,
                    expires_at = :expiresAt
                WHERE workspace_id = :workspaceId
                """)
                .param(
                        "createdAt",
                        toOffsetDateTime(expiresAt.minus(WorkspaceInvitation.VALIDITY_PERIOD))
                )
                .param(
                        "expiresAt",
                        toOffsetDateTime(expiresAt)
                )
                .param(
                        "workspaceId",
                        workspaceId
                )
                .update();
    }

    private void deleteInvitations(Long workspaceId) {
        jdbcClient.sql("""
                DELETE FROM workspace_invitations
                WHERE workspace_id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .update();
    }

    private List<String> invitationSnapshot(Long workspaceId) {
        return jdbcClient.sql("""
                SELECT to_jsonb(invitation)::text
                FROM workspace_invitations invitation
                WHERE workspace_id = :workspaceId
                ORDER BY id
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(String.class)
                .list();
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String uniqueValue(String prefix) {
        return prefix + UUID.randomUUID()
                .toString()
                .replace(
                        "-",
                        ""
                )
                .substring(
                        0,
                        12
                );
    }

    private String changeFirstLetterCase(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character)) {
                return value.substring(
                        0,
                        index
                ) + Character.toLowerCase(character) + value.substring(index + 1);
            }
            if (Character.isLowerCase(character)) {
                return value.substring(
                        0,
                        index
                ) + Character.toUpperCase(character) + value.substring(index + 1);
            }
        }
        throw new IllegalStateException("링크 토큰에 대소문자를 바꿀 문자가 없습니다");
    }

    private record WorkspaceFixture(
            Long workspaceId,
            AuthenticatedMember member
    ) {
    }
}
