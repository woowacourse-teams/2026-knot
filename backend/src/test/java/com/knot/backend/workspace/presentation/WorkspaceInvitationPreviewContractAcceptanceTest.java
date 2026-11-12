package com.knot.backend.workspace.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.WorkspaceInvitationSecretGenerator;
import com.knot.backend.workspace.application.WorkspaceInvitationSecretKind;
import com.knot.backend.workspace.application.WorkspaceInvitationSecretHasher;
import com.knot.backend.workspace.application.WorkspaceInvitationService;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ExtendWith(OutputCaptureExtension.class)
@IsolatedPostgresTest
@Import({WorkspaceInvitationPreviewContractAcceptanceTest.ClockConfiguration.class})
@TestApplicationProperties
@SpringBootTest(properties = "knot.api-docs.enabled=true")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceInvitationPreviewContractAcceptanceTest {
    private static final Instant NOW = Instant.now()
            .truncatedTo(ChronoUnit.MICROS);

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceInvitationSecretHasher workspaceInvitationSecretHasher;
    private final WorkspaceInvitationSecretGenerator generator;
    private final WorkspaceInvitationService service;

    WorkspaceInvitationPreviewContractAcceptanceTest(
            MockMvc mvc,
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            WorkspaceInvitationSecretHasher workspaceInvitationSecretHasher,
            WorkspaceInvitationSecretGenerator generator,
            WorkspaceInvitationService service
    ) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceInvitationSecretHasher = workspaceInvitationSecretHasher;
        this.generator = generator;
        this.service = service;
    }

    @ParameterizedTest
    @CsvSource({"CODE,1,200", "LINK,1,200", "CODE,0,404", "LINK,0,404", "CODE,-1,404", "LINK,-1,404"})
    @DisplayName("코드와 링크는 공통 만료 1마이크로초 전까지 유효하고 정각부터 함께 거절된다")
    void commonExpiry(
            String kind,
            long remainingMicros,
            int expectedStatus
    ) throws Exception {
        // given
        Fixture fixture = fixture();
        Credential credential = invitation(
                fixture.workspaceId(),
                NOW.minusSeconds(86400)
                        .plus(
                                remainingMicros,
                                ChronoUnit.MICROS
                        ),
                false
        );
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                credential.value(kind),
                remote()
        );

        // then
        response.andExpect(status().is(expectedStatus))
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
        if (expectedStatus == 200) {
            assertPreview(
                    response,
                    fixture.workspaceId()
            );
        } else {
            assertNotFound(response);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"CODE,INVALIDATED", "LINK,INVALIDATED", "CODE,DELETED", "LINK,DELETED"})
    @DisplayName("다른 유효 초대가 있어도 과거 무효화·삭제 대상은 공통 404이며 부활하지 않는다")
    void invalidStateNeverFallsBack(
            String kind,
            String state
    ) throws Exception {
        // given
        Fixture fixture = fixture();
        Credential credential = invitation(
                fixture.workspaceId(),
                NOW.minusSeconds(60),
                state.equals("INVALIDATED")
        );
        invitation(
                fixture.workspaceId(),
                NOW,
                false
        );
        if (state.equals("DELETED")) {
            jdbc.sql("UPDATE workspaces SET deleted_at = :now WHERE id = :id")
                    .param(
                            "now",
                            NOW.atOffset(ZoneOffset.UTC)
                    )
                    .param(
                            "id",
                            fixture.workspaceId()
                    )
                    .update();
        }
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                credential.value(kind),
                remote()
        );

        // then
        assertNotFound(response);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODE", "LINK"})
    @DisplayName("발급자가 탈퇴해도 남은 멤버의 워크스페이스 초대는 유효하다")
    void issuerLeavingDoesNotInvalidate(String kind) throws Exception {
        // given
        Fixture fixture = fixture();
        long issuer = member();
        join(
                fixture.workspaceId(),
                issuer,
                "MEMBER"
        );
        WorkspaceInvitationResult issued = service.issue(
                fixture.workspaceId(),
                issuer
        );
        jdbc.sql("UPDATE workspace_members SET left_at = :now WHERE workspace_id = :workspace AND member_id = :member")
                .param(
                        "now",
                        NOW.atOffset(ZoneOffset.UTC)
                )
                .param(
                        "workspace",
                        fixture.workspaceId()
                )
                .param(
                        "member",
                        issuer
                )
                .update();
        String credential = kind.equals("CODE") ? issued.code() : issued.linkToken();
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                credential,
                remote()
        );

        // then
        assertPreview(
                response,
                fixture.workspaceId()
        );
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("복수 초대와 다른 워크스페이스를 반복 미리보기해도 가입·소모·연장·로그 노출이 없다")
    void repeatedPreviewsPreserveAllRows(CapturedOutput output) throws Exception {
        // given
        Fixture first = fixture();
        Fixture second = fixture();
        Credential one = invitation(
                first.workspaceId(),
                NOW.minusSeconds(100),
                false
        );
        Credential two = invitation(
                first.workspaceId(),
                NOW.minusSeconds(50),
                false
        );
        Credential other = invitation(
                second.workspaceId(),
                NOW,
                false
        );
        List<String> credentials = List.of(
                one.code(),
                one.link(),
                two.code(),
                two.link(),
                other.code(),
                other.link(),
                one.code(),
                two.link()
        );
        List<Long> workspaces = List.of(
                first.workspaceId(),
                first.workspaceId(),
                first.workspaceId(),
                first.workspaceId(),
                second.workspaceId(),
                second.workspaceId(),
                first.workspaceId(),
                first.workspaceId()
        );
        List<String> before = snapshot();

        // when
        List<ResultActions> responses = previewAll(credentials);

        // then
        for (int i = 0; i < responses.size(); i++) {
            assertPreview(
                    responses.get(i),
                    workspaces.get(i)
            );
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(credentials.toArray(String[]::new));
    }

    @ParameterizedTest
    @CsvSource({"1,200", "0,404"})
    @DisplayName("기존 숫자 포함 코드의 소문자·주변 공백은 만료 전까지만 호환된다")
    void legacyCodeNormalizesUntilExpiry(
            long remainingMicros,
            int expectedStatus
    ) throws Exception {
        // given
        Fixture fixture = fixture();
        Credential credential = invitation(
                fixture.workspaceId(),
                NOW.minusSeconds(86400)
                        .plus(
                                remainingMicros,
                                ChronoUnit.MICROS
                        ),
                false
        );
        String legacy = "23" + credential.code()
                .substring(2);
        jdbc.sql("""
                UPDATE workspace_invitations SET invite_code_hash = :hash
                WHERE link_token_hash = :link
                """)
                .param(
                        "hash",
                        workspaceInvitationSecretHasher.hash(
                                WorkspaceInvitationSecretKind.INVITE_CODE,
                                legacy
                        )
                )
                .param(
                        "link",
                        workspaceInvitationSecretHasher.hash(
                                WorkspaceInvitationSecretKind.LINK_TOKEN,
                                credential.link()
                        )
                )
                .update();
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                " " + legacy.toLowerCase(Locale.ROOT) + " ",
                remote()
        );

        // then
        response.andExpect(status().is(expectedStatus))
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASE", "SPACE", "MISSING", "MALFORMED"})
    @DisplayName("잘못된 링크·코드는 유효한 다른 초대로 대체하지 않고 비밀값 없는 공통 404를 반환한다")
    void exactMatchAndUnifiedFailure(
            String variant,
            CapturedOutput output
    ) throws Exception {
        // given
        Fixture fixture = fixture();
        Credential credential = invitation(
                fixture.workspaceId(),
                NOW,
                false
        );
        String input = switch (variant) {
            case "CASE" -> credential.link()
                    .toUpperCase(Locale.ROOT);
            case "SPACE" -> " " + credential.link() + " ";
            case "MALFORMED" -> "AB-12!";
            default -> "missing-" + UUID.randomUUID();
        };
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                input,
                remote()
        );

        // then
        assertNotFound(response);
        assertThat(
                response.andReturn()
                        .getResponse()
                        .getContentAsString()
        ).doesNotContain(
                input,
                credential.code(),
                credential.link()
        );
        assertThat(output.getAll()).doesNotContain(
                input,
                credential.code(),
                credential.link()
        );
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("코드 성공과 형식 오류가 같은 30회 제한을 소비하고 31번째는 Retry-After와 no-store를 반환한다")
    void codeLimitIncludesFailures() throws Exception {
        // given
        Credential credential = invitation(
                fixture().workspaceId(),
                NOW,
                false
        );
        String address = remote();
        for (int i = 0; i < 30; i++) {
            preview(
                    i % 2 == 0 ? credential.code() : "AB-12!",
                    address
            ).andExpect(status().is(i % 2 == 0 ? 200 : 404));
        }
        List<String> before = snapshot();

        // when
        ResultActions response = preview(
                credential.code(),
                address
        );

        // then
        response.andExpect(status().isTooManyRequests())
                .andExpect(
                        header().string(
                                "Retry-After",
                                "60"
                        )
                )
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_RATE_LIMIT_EXCEEDED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("코드 조회 한도를 소진한 주소에서도 정확한 링크는 조회된다")
    void linkIsIndependentOfCodeLimit() throws Exception {
        // given
        Fixture fixture = fixture();
        Credential credential = invitation(
                fixture.workspaceId(),
                NOW,
                false
        );
        String address = remote();
        for (int i = 0; i < 30; i++) {
            preview(
                    "AB-12!",
                    address
            ).andExpect(status().isNotFound());
        }

        // when
        ResultActions response = preview(
                credential.link(),
                address
        );

        // then
        assertPreview(
                response,
                fixture.workspaceId()
        );
        response.andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("OpenAPI는 공개 조회와 FE 확인 책임을 분리하고 최소 응답 계약을 제공한다")
    void openApiContract() throws Exception {
        // given
        String path = "/v3/api-docs";

        // when
        ResultActions response = mvc.perform(get(path));

        // then
        response.andExpect(status().isOk());
        JsonNode document = body(response);
        JsonNode operation = document.path("paths")
                .path("/api/v1/invitations/{tokenOrCode}")
                .path("get");
        assertThat(
                operation.path("description")
                        .asText()
        ).contains(
                "가입하거나 초대를 소비하지",
                "함께 만료",
                "오래된 응답",
                "오류 화면 후 코드 입력"
        );
        assertThat(
                operation.path("security")
                        .isEmpty()
        ).isTrue();
        assertThat(
                operation.path("responses")
                        .propertyNames()
        ).contains(
                "200",
                "404",
                "429"
        );
        assertThat(
                document.path("components")
                        .path("schemas")
                        .path("WorkspaceInvitationPreviewResponse")
                        .path("properties")
                        .propertyNames()
        ).containsExactlyInAnyOrder(
                "workspaceId",
                "workspaceName"
        );
    }

    private Fixture fixture() {
        long owner = member();
        long workspace = jdbc.sql("INSERT INTO workspaces (name, created_at) VALUES ('미리보기 팀', :now) RETURNING id")
                .param(
                        "now",
                        NOW.atOffset(ZoneOffset.UTC)
                )
                .query(Long.class)
                .single();
        join(
                workspace,
                owner,
                "OWNER"
        );
        return new Fixture(workspace);
    }

    private long member() {
        return jdbc.sql("INSERT INTO members (nickname) VALUES ('미리보기 멤버') RETURNING id")
                .query(Long.class)
                .single();
    }

    private void join(
            long workspace,
            long member,
            String role
    ) {
        jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspace, :member, :role, :now)
                """)
                .param(
                        "workspace",
                        workspace
                )
                .param(
                        "member",
                        member
                )
                .param(
                        "role",
                        role
                )
                .param(
                        "now",
                        NOW.atOffset(ZoneOffset.UTC)
                )
                .update();
    }

    private Credential invitation(
            long workspace,
            Instant createdAt,
            boolean invalidated
    ) {
        String code = generator.generate()
                .code();
        String link = "link-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO workspace_invitations (workspace_id, link_token_hash, invite_code_hash,
                    created_at, expires_at, invalidated_at)
                VALUES (:workspace, :linkHash, :codeHash, :created, :expires, :invalidated)
                """)
                .param(
                        "workspace",
                        workspace
                )
                .param(
                        "linkHash",
                        workspaceInvitationSecretHasher.hash(
                                WorkspaceInvitationSecretKind.LINK_TOKEN,
                                link
                        )
                )
                .param(
                        "codeHash",
                        workspaceInvitationSecretHasher.hash(
                                WorkspaceInvitationSecretKind.INVITE_CODE,
                                code
                        )
                )
                .param(
                        "created",
                        createdAt.atOffset(ZoneOffset.UTC)
                )
                .param(
                        "expires",
                        createdAt.plusSeconds(86400)
                                .atOffset(ZoneOffset.UTC)
                )
                .param(
                        "invalidated",
                        invalidated ? createdAt.atOffset(ZoneOffset.UTC) : null
                )
                .update();
        return new Credential(
                code,
                link
        );
    }

    private ResultActions preview(
            String credential,
            String address
    ) throws Exception {
        return mvc.perform(
                get(
                        "/api/v1/invitations/{credential}",
                        credential
                ).with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
        );
    }

    private List<ResultActions> previewAll(List<String> credentials) throws Exception {
        List<ResultActions> responses = new ArrayList<>();
        String address = remote();
        for (String credential : credentials) {
            responses.add(
                    preview(
                            credential,
                            address
                    )
            );
        }
        return responses;
    }

    private void assertPreview(
            ResultActions response,
            long workspace
    ) throws Exception {
        response.andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
        JsonNode body = body(response);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "workspaceId",
                "workspaceName"
        );
        assertThat(
                body.path("workspaceId")
                        .asLong()
        ).isEqualTo(workspace);
        assertThat(
                body.path("workspaceName")
                        .asText()
        ).isEqualTo("미리보기 팀");
    }

    private void assertNotFound(ResultActions response) throws Exception {
        response.andExpect(status().isNotFound())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                )
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_PREVIEW_NOT_FOUND"));
    }

    private JsonNode body(ResultActions response) throws Exception {
        return objectMapper.readTree(
                response.andReturn()
                        .getResponse()
                        .getContentAsString()
        );
    }

    private List<String> snapshot() {
        return jdbc.sql("""
                SELECT 'invitation:' || row_to_json(i)::text AS snapshot FROM workspace_invitations i
                UNION ALL SELECT 'membership:' || row_to_json(m)::text FROM workspace_members m
                UNION ALL SELECT 'workspace:' || row_to_json(w)::text FROM workspaces w
                ORDER BY snapshot
                """)
                .query(String.class)
                .list();
    }

    private String remote() {
        return UUID.randomUUID()
                .toString();
    }

    private record Fixture(long workspaceId) {
    }

    private record Credential(
            String code,
            String link
    ) {
        String value(String kind) {
            return kind.equals("CODE") ? code : link;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock previewContractClock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }
    }
}
