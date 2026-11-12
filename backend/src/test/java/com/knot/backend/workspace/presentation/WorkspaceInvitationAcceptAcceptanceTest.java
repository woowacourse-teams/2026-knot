package com.knot.backend.workspace.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.member.domain.Member;
import com.knot.backend.member.domain.MemberRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.WorkspaceInvitationService;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import com.knot.backend.workspace.domain.model.Workspace;
import com.knot.backend.workspace.domain.model.WorkspaceInvitation;
import com.knot.backend.workspace.domain.model.WorkspaceMember;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.model.WorkspaceMemberRole;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ExtendWith(OutputCaptureExtension.class)
@IsolatedPostgresTest
@Import({WorkspaceInvitationAcceptAcceptanceTest.ClockConfiguration.class})
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceInvitationAcceptAcceptanceTest {
    private static final Instant BASE_TIME = Instant.parse("2026-08-31T12:00:00Z");

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final AuthTokenProvider authTokenProvider;
    private final WorkspaceInvitationService invitationService;
    private final MemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final JdbcClient jdbcClient;
    private final MutableClock clock;

    WorkspaceInvitationAcceptAcceptanceTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            AuthTokenProvider authTokenProvider,
            WorkspaceInvitationService invitationService,
            MemberRepository memberRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            JdbcClient jdbcClient,
            MutableClock clock
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.authTokenProvider = authTokenProvider;
        this.invitationService = invitationService;
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @BeforeEach
    void resetClock() {
        clock.reset(BASE_TIME);
    }

    @DisplayName("소문자와 주변 공백이 있는 코드로 참여하면 MEMBER를 만들고 201과 최소 응답을 반환한다")
    @Test
    void accept_success_normalizedCodeCreatesMember() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("코드 참여 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String normalizedInput = " " + fixture.invitation()
                .code()
                .toLowerCase(Locale.ROOT) + " ";

        // when
        ResultActions result = performAccept(
                normalizedInput,
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.workspaceName").value("코드 참여 팀"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.linkToken").doesNotExist());
        assertThat(
                countMemberships(
                        fixture.workspaceId(),
                        joiningMember.getMemberId()
                )
        ).isEqualTo(1);
        assertThat(
                findRole(
                        fixture.workspaceId(),
                        joiningMember.getMemberId()
                )
        ).isEqualTo("MEMBER");
        assertThat(
                findLastViewed(
                        fixture.workspaceId(),
                        joiningMember.getMemberId()
                )
        ).isFalse();
    }

    @DisplayName("원문이 일치하는 링크 토큰으로 참여하면 MEMBER를 만들고 201을 반환한다")
    @Test
    void accept_success_exactLinkTokenCreatesMember() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("링크 참여 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .linkToken(),
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.workspaceName").value("링크 참여 팀"));
        assertThat(
                countMemberships(
                        fixture.workspaceId(),
                        joiningMember.getMemberId()
                )
        ).isEqualTo(1);
    }

    @DisplayName("응답 유실 뒤 같은 사용자가 다시 참여하면 멤버십을 추가하지 않고 200을 반환한다")
    @Test
    void accept_success_retryReturnsExistingMembership() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("참여 재시도 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        performAccept(
                fixture.invitation()
                        .code(),
                joiningMember,
                uniqueRemoteAddress()
        ).andExpect(status().isCreated());

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .code(),
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()));
        assertThat(
                countMemberships(
                        fixture.workspaceId(),
                        joiningMember.getMemberId()
                )
        ).isEqualTo(1);
    }

    @DisplayName("OWNER가 자기 초대를 수락하면 역할을 바꾸지 않고 200을 반환한다")
    @Test
    void accept_success_ownerKeepsMembership() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("OWNER 자기 참여 팀");

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .linkToken(),
                fixture.owner(),
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()));
        assertThat(
                countMemberships(
                        fixture.workspaceId(),
                        fixture.owner()
                                .getMemberId()
                )
        ).isEqualTo(1);
        assertThat(
                findRole(
                        fixture.workspaceId(),
                        fixture.owner()
                                .getMemberId()
                )
        ).isEqualTo("OWNER");
    }

    @DisplayName("유효한 CSRF가 있어도 인증되지 않은 참여 요청은 401을 반환하고 상태를 저장하지 않는다")
    @Test
    void accept_failure_unauthenticatedWithValidCsrf() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("미인증 참여 팀");
        long membershipCount = countAllMemberships(fixture.workspaceId());

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(
                                requestBody(
                                        fixture.invitation()
                                                .code()
                                )
                        )
                        .with(csrf())
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
        assertThat(countAllMemberships(fixture.workspaceId())).isEqualTo(membershipCount);
    }

    @DisplayName("인증과 CSRF가 모두 없는 참여 요청은 403을 반환한다")
    @Test
    void accept_failure_missingAuthenticationAndCsrf() throws Exception {
        // given
        String requestBody = requestBody("ABCDEF");

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("인증됐지만 CSRF가 없는 참여 요청은 403을 반환한다")
    @Test
    void accept_failure_missingCsrf() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ABCDEF"))
                        .cookie(authenticatedCookie(joiningMember))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("인증됐지만 CSRF가 불일치하는 참여 요청은 403을 반환한다")
    @Test
    void accept_failure_invalidCsrf() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ABCDEF"))
                        .cookie(authenticatedCookie(joiningMember))
                        .with(csrf().useInvalidToken())
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("JSON 본문이 누락된 참여 요청은 400을 반환한다")
    @Test
    void accept_failure_missingRequestBody() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAcceptBody(
                "",
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("잘못된 JSON 본문으로 참여하면 400을 반환한다")
    @Test
    void accept_failure_malformedRequestBody() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAcceptBody(
                "{",
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("credential 필드가 누락된 참여 요청은 400과 필드 오류를 반환한다")
    @Test
    void accept_failure_missingCredentialField() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAcceptBody(
                "{}",
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("credential"))
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("credential이 null인 참여 요청은 400을 반환한다")
    @Test
    void accept_failure_nullCredential() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAcceptBody(
                "{\"credential\":null}",
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @DisplayName("credential이 빈 문자열인 참여 요청은 400을 반환한다")
    @Test
    void accept_failure_blankCredential() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();

        // when
        ResultActions result = performAcceptBody(
                requestBody("   "),
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @DisplayName("형식이 잘못된 6자리 코드는 원인을 구분하지 않는 404를 반환한다")
    @Test
    void accept_failure_invalidCodeLooksLikeNotFound() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("잘못된 코드 참여 팀");

        // when
        ResultActions result = performAccept(
                "ABC1O0",
                fixture.owner(),
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"))
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("존재하지 않는 유효 형식 코드는 기존 멤버에게도 통합 404를 반환한다")
    @Test
    void accept_failure_missingCodeBeforeMembershipCheck() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("없는 코드 참여 팀");

        // when
        ResultActions result = performAccept(
                "AAAAAA",
                fixture.owner(),
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"))
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("과거 재발급으로 무효화된 코드는 기존 멤버에게도 통합 404를 반환한다")
    @Test
    void accept_failure_reissuedCodeBeforeMembershipCheck() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("재발급 참여 팀");
        String invalidatedCode = fixture.invitation()
                .code();
        jdbcClient.sql("UPDATE workspace_invitations SET invalidated_at = created_at WHERE workspace_id = :workspaceId")
                .param(
                        "workspaceId",
                        fixture.workspaceId()
                )
                .update();

        // when
        ResultActions result = performAccept(
                invalidatedCode,
                fixture.owner(),
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
        assertThat(
                findRole(
                        fixture.workspaceId(),
                        fixture.owner()
                                .getMemberId()
                )
        ).isEqualTo("OWNER");
    }

    @DisplayName("만료된 코드는 기존 멤버에게도 통합 404를 반환한다")
    @Test
    void accept_failure_expiredCodeBeforeMembershipCheck() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("만료 참여 팀");
        expireInvitation(fixture.workspaceId());

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .code(),
                fixture.owner(),
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("대소문자나 주변 공백이 바뀐 링크 토큰은 통합 404를 반환한다")
    @Test
    void accept_failure_changedLinkToken() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("링크 exact 참여 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String changedCredential = " " + changeFirstLetterCase(
                fixture.invitation()
                        .linkToken()
        ) + " ";

        // when
        ResultActions result = performAccept(
                changedCredential,
                joiningMember,
                uniqueRemoteAddress()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    @DisplayName("코드 미리보기와 참여는 같은 30회 한도를 공유하고 31번째에 429를 반환한다")
    @Test
    void accept_failure_sharedRateLimitWithPreview() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("공유 제한 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String remoteAddress = uniqueRemoteAddress();
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                29
        );
        performAccept(
                fixture.invitation()
                        .code(),
                joiningMember,
                remoteAddress
        ).andExpect(status().isCreated());

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
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                HttpHeaders.RETRY_AFTER + ", X-Request-ID"
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("잘못된 6자리 코드 참여도 공유 한도를 소비한다")
    @Test
    void accept_failure_invalidCodeConsumesSharedRateLimit() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String remoteAddress = uniqueRemoteAddress();
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                29
        );
        performAccept(
                "ABC1O0",
                joiningMember,
                remoteAddress
        ).andExpect(status().isNotFound());

        // when
        ResultActions result = performAccept(
                "X35D3S",
                joiningMember,
                remoteAddress
        );

        // then
        result.andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                );
    }

    @DisplayName("존재하지 않는 유효 형식 코드 참여도 공유 한도를 소비한다")
    @Test
    void accept_failure_missingCodeConsumesSharedRateLimit() throws Exception {
        // given
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String remoteAddress = uniqueRemoteAddress();
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                29
        );
        performAccept(
                "AAAAAA",
                joiningMember,
                remoteAddress
        ).andExpect(status().isNotFound());

        // when
        ResultActions result = performAccept(
                "X35D3S",
                joiningMember,
                remoteAddress
        );

        // then
        result.andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @DisplayName("링크 토큰 참여는 코드 한도를 소비하지 않는다")
    @Test
    void accept_success_linkTokenDoesNotConsumeCodeRateLimit() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("링크 제한 제외 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String remoteAddress = uniqueRemoteAddress();
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                30
        );

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .linkToken(),
                joiningMember,
                remoteAddress
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()));
    }

    @DisplayName("코드 참여 한도는 고정 1분 창이 지나면 초기화된다")
    @Test
    void accept_success_rateLimitWindowResets() throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("제한 초기화 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String remoteAddress = uniqueRemoteAddress();
        consumePreviewAttempts(
                "ABC1O0",
                remoteAddress,
                30
        );
        clock.advance(Duration.ofMinutes(1));

        // when
        ResultActions result = performAccept(
                fixture.invitation()
                        .code(),
                joiningMember,
                remoteAddress
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()));
    }

    @DisplayName("초대 참여는 코드와 링크 토큰 원문을 로그에 남기지 않는다")
    @Test
    void accept_success_doesNotLogRawCredential(CapturedOutput output) throws Exception {
        // given
        InvitationFixture fixture = createInvitationFixture("참여 로그 팀");
        AuthenticatedMember joiningMember = createAuthenticatedMember();
        String code = fixture.invitation()
                .code();
        String linkToken = fixture.invitation()
                .linkToken();

        // when
        performAcceptCredentials(
                fixture,
                joiningMember,
                code,
                linkToken
        );

        // then
        assertThat(output.getAll()).doesNotContain(
                code,
                linkToken
        );
    }

    private InvitationFixture createInvitationFixture(String workspaceName) {
        Member ownerMember = createMember();
        Workspace workspace = workspaceRepository.save(
                Workspace.create(
                        workspaceName,
                        clock.instant()
                                .minusSeconds(1)
                )
        );
        workspaceMemberRepository.save(
                WorkspaceMember.create(
                        workspace.getId(),
                        ownerMember.getId(),
                        WorkspaceMemberRole.OWNER,
                        clock.instant()
                                .minusSeconds(1)
                )
        );
        AuthenticatedMember owner = authenticatedMember(ownerMember);
        WorkspaceInvitationResult invitation = invitationService.issue(
                workspace.getId(),
                owner.getMemberId()
        );
        return new InvitationFixture(
                workspace.getId(),
                owner,
                invitation
        );
    }

    private AuthenticatedMember createAuthenticatedMember() {
        return authenticatedMember(createMember());
    }

    private Member createMember() {
        return memberRepository.save(
                Member.create(
                        uniqueNickname(),
                        null
                )
        );
    }

    private AuthenticatedMember authenticatedMember(Member member) {
        return AuthenticatedMember.of(
                member.getId(),
                member.getNickname(),
                member.getProfileImageUrl()
        );
    }

    private ResultActions performAccept(
            String credential,
            AuthenticatedMember member,
            String remoteAddress
    ) throws Exception {
        return performAcceptBody(
                requestBody(credential),
                member,
                remoteAddress
        );
    }

    private ResultActions performAcceptBody(
            String body,
            AuthenticatedMember member,
            String remoteAddress
    ) throws Exception {
        return mockMvc.perform(
                post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authenticatedCookie(member))
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
        );
    }

    private void performAcceptCredentials(
            InvitationFixture fixture,
            AuthenticatedMember joiningMember,
            String code,
            String linkToken
    ) throws Exception {
        performAccept(
                code,
                joiningMember,
                uniqueRemoteAddress()
        ).andExpect(status().isCreated());
        performAccept(
                linkToken,
                joiningMember,
                uniqueRemoteAddress()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(fixture.workspaceId()));
    }

    private String requestBody(String credential) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of(
                        "credential",
                        credential
                )
        );
    }

    private Cookie authenticatedCookie(AuthenticatedMember member) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                authTokenProvider.issue(member)
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
        Instant expiresAt = clock.instant()
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

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private long countMemberships(
            Long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND member_id = :memberId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .single();
    }

    private long countAllMemberships(Long workspaceId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Long.class)
                .single();
    }

    private String findRole(
            Long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT role
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND member_id = :memberId
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

    private boolean findLastViewed(
            Long workspaceId,
            long memberId
    ) {
        return jdbcClient.sql("""
                SELECT last_viewed
                FROM workspace_members
                WHERE workspace_id = :workspaceId
                  AND member_id = :memberId
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .query(Boolean.class)
                .single();
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

    private String uniqueNickname() {
        return "member" + UUID.randomUUID()
                .toString()
                .replace(
                        "-",
                        ""
                )
                .replaceAll(
                        "[0-9]",
                        "g"
                )
                .substring(
                        0,
                        10
                );
    }

    private String uniqueRemoteAddress() {
        return "test-" + UUID.randomUUID();
    }

    private record InvitationFixture(
            Long workspaceId,
            AuthenticatedMember owner,
            WorkspaceInvitationResult invitation
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void reset(Instant newInstant) {
            instant.set(newInstant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }
}
