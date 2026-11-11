package com.knot.backend.workspace.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import com.knot.backend.workspace.application.WorkspaceInvitationSecretGenerator;
import com.knot.backend.workspace.application.WorkspaceInvitationService;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationResult;
import com.knot.backend.workspace.application.dto.result.WorkspaceInvitationSecrets;
import com.knot.backend.workspace.domain.WorkspaceInvitationSecretCollisionException;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkspaceMultipleInvitationAcceptanceTest {
    @MockitoSpyBean
    private WorkspaceInvitationSecretGenerator workspaceInvitationSecretGenerator;
    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AuthTokenProvider tokens;
    private final WorkspaceInvitationService service;

    WorkspaceMultipleInvitationAcceptanceTest(
            MockMvc mvc,
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            AuthTokenProvider tokens,
            WorkspaceInvitationService service
    ) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.service = service;
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MEMBER"})
    @DisplayName("OWNER와 MEMBER의 반복 발급은 새 201과 A-Z 코드를 반환하고 이전 초대를 보존한다")
    void issue_createsNewInvitation(String role) throws Exception {
        // given
        Fixture fixture = fixture(role);
        JsonNode first = body(issue(fixture));
        List<String> before = rows(fixture.workspaceId());

        // when
        ResultActions response = issue(fixture);

        // then
        response.andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
        JsonNode second = body(response);
        assertThat(
                second.get("code")
                        .asText()
        ).matches("[A-Z]{6}")
                .isNotEqualTo(
                        first.get("code")
                                .asText()
                );
        assertThat(rows(fixture.workspaceId())).hasSize(2)
                .containsAll(before);
        assertThat(
                jdbc.sql("""
                        SELECT bool_and(expires_at = created_at + INTERVAL '24 hours')
                        FROM workspace_invitations WHERE workspace_id = :id
                        """)
                        .param(
                                "id",
                                fixture.workspaceId()
                        )
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        mvc.perform(
                get(
                        "/api/v1/invitations/{value}",
                        first.get("code")
                                .asText()
                )
        )
                .andExpect(status().isOk());
        mvc.perform(
                get(
                        "/api/v1/invitations/{value}",
                        first.get("linkToken")
                                .asText()
                )
        )
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"outsider,403", "left,403", "deleted,404", "missing,404"})
    @DisplayName("비멤버·탈퇴자·삭제되거나 없는 Workspace에는 초대를 발급하지 않는다")
    void issue_rejectsInaccessibleWorkspace(
            String state,
            int expected
    ) throws Exception {
        // given
        Fixture original = fixture("MEMBER");
        Fixture fixture = original;
        if (state.equals("outsider")) {
            fixture = new Fixture(
                    original.workspaceId(),
                    member()
            );
        } else if (state.equals("left")) {
            jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP WHERE workspace_id = :id")
                    .param(
                            "id",
                            original.workspaceId()
                    )
                    .update();
        } else if (state.equals("deleted")) {
            jdbc.sql("UPDATE workspaces SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param(
                            "id",
                            original.workspaceId()
                    )
                    .update();
        } else {
            fixture = new Fixture(
                    Long.MAX_VALUE,
                    original.memberId()
            );
        }

        // when
        ResultActions response = issue(fixture);

        // then
        response.andExpect(status().is(expected))
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
        assertThat(rows(original.workspaceId())).isEmpty();
    }

    @Test
    @DisplayName("양수가 아닌 Workspace ID의 초대 발급 요청은 400을 반환한다")
    void issue_rejectsInvalidWorkspaceId() throws Exception {
        // given
        Fixture fixture = new Fixture(
                0L,
                fixture("OWNER").memberId()
        );

        // when
        ResultActions response = issue(fixture);

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_ID"));
    }

    @Test
    @DisplayName("복수 발급도 인증과 CSRF를 요구한다")
    void issue_requiresAuthenticationAndCsrf() throws Exception {
        // given
        Fixture fixture = fixture("OWNER");

        // when
        ResultActions response = mvc.perform(
                post(
                        "/api/v1/workspaces/{id}/invitations",
                        fixture.workspaceId()
                ).with(csrf())
        );

        // then
        response.andExpect(status().isUnauthorized());
        mvc.perform(
                post(
                        "/api/v1/workspaces/{id}/invitations",
                        fixture.workspaceId()
                ).cookie(cookie(fixture.memberId()))
        )
                .andExpect(status().isForbidden());
        assertThat(rows(fixture.workspaceId())).isEmpty();
    }

    @Test
    @DisplayName("같은 Workspace의 동시 발급은 각각 새 초대를 저장한다")
    void issue_concurrentRequestsCreateDistinctInvitations() throws Exception {
        // given
        Fixture fixture = fixture("OWNER");
        long otherMember = member();
        join(
                fixture.workspaceId(),
                otherMember,
                "MEMBER"
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<WorkspaceInvitationResult> first = () -> {
            barrier.await();
            return service.issue(
                    fixture.workspaceId(),
                    fixture.memberId()
            );
        };
        Callable<WorkspaceInvitationResult> second = () -> {
            barrier.await();
            return service.issue(
                    fixture.workspaceId(),
                    otherMember
            );
        };

        // when
        List<WorkspaceInvitationResult> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<WorkspaceInvitationResult>> futures = executor.invokeAll(
                    List.of(
                            first,
                            second
                    )
            );
            results = List.of(
                    futures.get(0)
                            .get(),
                    futures.get(1)
                            .get()
            );
        }

        // then
        assertThat(results).extracting(WorkspaceInvitationResult::code)
                .doesNotHaveDuplicates();
        assertThat(rows(fixture.workspaceId())).hasSize(2);
    }

    @Test
    @DisplayName("hash 충돌은 새 transaction에서 재시도하며 이전 초대를 유지한다")
    void issue_retriesCollision() {
        // given
        Fixture fixture = fixture("OWNER");
        WorkspaceInvitationResult first = service.issue(
                fixture.workspaceId(),
                fixture.memberId()
        );
        doReturn(
                new WorkspaceInvitationSecrets(
                        first.code(),
                        first.linkToken()
                ),
                new WorkspaceInvitationSecrets(
                        "IOIOIO",
                        "new-link-token-for-retry"
                )
        ).when(workspaceInvitationSecretGenerator)
                .generate();
        List<String> before = rows(fixture.workspaceId());

        // when
        WorkspaceInvitationResult result = service.issue(
                fixture.workspaceId(),
                fixture.memberId()
        );

        // then
        assertThat(result.code()).isEqualTo("IOIOIO");
        assertThat(rows(fixture.workspaceId())).hasSize(2)
                .containsAll(before);
        assertThat(
                service.preview(
                        "ioioio",
                        "retry-test"
                )
                        .workspaceId()
        ).isEqualTo(fixture.workspaceId());
    }

    @Test
    @DisplayName("hash 충돌 3회 소진은 새 행을 남기거나 기존 초대를 무효화하지 않는다")
    void issue_exhaustedCollisionPreservesData() {
        // given
        Fixture fixture = fixture("OWNER");
        WorkspaceInvitationResult first = service.issue(
                fixture.workspaceId(),
                fixture.memberId()
        );
        doReturn(
                new WorkspaceInvitationSecrets(
                        first.code(),
                        first.linkToken()
                )
        ).when(workspaceInvitationSecretGenerator)
                .generate();
        List<String> before = rows(fixture.workspaceId());

        // when
        ThrowingCallable action = () -> service.issue(
                fixture.workspaceId(),
                fixture.memberId()
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceInvitationSecretCollisionException.class);
        assertThat(rows(fixture.workspaceId())).isEqualTo(before);
    }

    private Fixture fixture(String role) {
        long memberId = member();
        long workspaceId = jdbc
                .sql("INSERT INTO workspaces (name, created_at) VALUES ('복수 초대 팀', CURRENT_TIMESTAMP) RETURNING id")
                .query(Long.class)
                .single();
        join(
                workspaceId,
                memberId,
                role
        );
        return new Fixture(
                workspaceId,
                memberId
        );
    }

    private long member() {
        return jdbc.sql("INSERT INTO members (nickname) VALUES ('초대 멤버') RETURNING id")
                .query(Long.class)
                .single();
    }

    private void join(
            long workspaceId,
            long memberId,
            String role
    ) {
        jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, :role, CURRENT_TIMESTAMP)
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
                .update();
    }

    private ResultActions issue(Fixture fixture) throws Exception {
        return mvc.perform(
                post(
                        "/api/v1/workspaces/{id}/invitations",
                        fixture.workspaceId()
                ).cookie(cookie(fixture.memberId()))
                        .with(csrf())
        );
    }

    private Cookie cookie(long memberId) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "초대 멤버",
                                null
                        )
                )
        );
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        );
    }

    private List<String> rows(long workspaceId) {
        return jdbc.sql("SELECT row_to_json(i)::text FROM workspace_invitations i WHERE workspace_id = :id ORDER BY id")
                .param(
                        "id",
                        workspaceId
                )
                .query(String.class)
                .list();
    }

    private record Fixture(
            long workspaceId,
            long memberId
    ) {
    }
}
