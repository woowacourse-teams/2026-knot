package com.knot.backend.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
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
class WorkspaceLeaveRecordingDiscardAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    WorkspaceLeaveRecordingDiscardAcceptanceTest(
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
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_invitations, workspace_members, workspaces,
                    oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("MEMBER가 탈퇴하면 204 후 본인 녹음만 DISCARDED가 되고 OWNER 녹음은 유지된다")
    void leave_success_discardsLeavingMemberRecording() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace();
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
        long ownerRecordingId = startedRecordingId(
                workspaceId,
                ownerId,
                UUID.randomUUID()
        );
        long memberRecordingId = startedRecordingId(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );

        // when
        ResultActions result = leave(
                workspaceId,
                memberId
        );

        // then
        result.andExpect(status().isNoContent());
        assertThat(recordingStatus(memberRecordingId)).isEqualTo("DISCARDED");
        assertThat(recordingStatus(ownerRecordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("마지막 멤버가 탈퇴하면 Workspace 삭제와 함께 진행 중 녹음이 DISCARDED가 된다")
    void leave_success_lastMemberDiscardsRecording() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long workspaceId = saveWorkspace();
        saveWorkspaceMember(
                workspaceId,
                ownerId,
                "OWNER"
        );
        long recordingId = startedRecordingId(
                workspaceId,
                ownerId,
                UUID.randomUUID()
        );

        // when
        ResultActions result = leave(
                workspaceId,
                ownerId
        );

        // then
        result.andExpect(status().isNoContent());
        assertThat(recordingStatus(recordingId)).isEqualTo("DISCARDED");
        assertThat(workspaceDeleted(workspaceId)).isTrue();
    }

    @Test
    @DisplayName("OWNER가 승계 후 탈퇴하면 204 후 본인 녹음만 DISCARDED가 되고 승계자 녹음은 유지된다")
    void transferOwnership_success_discardsOwnerRecording() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long successorId = saveMember("successor");
        long workspaceId = saveWorkspace();
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
        long ownerRecordingId = startedRecordingId(
                workspaceId,
                ownerId,
                UUID.randomUUID()
        );
        long successorRecordingId = startedRecordingId(
                workspaceId,
                successorId,
                UUID.randomUUID()
        );
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/ownership-transfers",
                        workspaceId
                ).cookie(
                        accessTokenCookie(ownerId),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successorMemberId":%d}
                                """.formatted(successorId))
        );

        // then
        result.andExpect(status().isNoContent());
        assertThat(recordingStatus(ownerRecordingId)).isEqualTo("DISCARDED");
        assertThat(recordingStatus(successorRecordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("탈퇴 후 재가입해 같은 요청 키로 시작하면 폐기된 세션을 200으로 반환하고 되살리지 않는다")
    void start_success_replayDiscardedAfterRejoin() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace();
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
        UUID requestId = UUID.randomUUID();
        long recordingId = startedRecordingId(
                workspaceId,
                memberId,
                requestId
        );
        leave(
                workspaceId,
                memberId
        ).andExpect(status().isNoContent());
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );

        // when
        ResultActions result = startRecording(
                workspaceId,
                memberId,
                requestId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        assertThat(recordingStatus(recordingId)).isEqualTo("DISCARDED");
        assertThat(recordingCount(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴 후 재가입하면 새 요청 키로 새 녹음을 시작할 수 있다")
    void start_success_newRequestAfterRejoin() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace();
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
        startedRecordingId(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        leave(
                workspaceId,
                memberId
        ).andExpect(status().isNoContent());
        saveWorkspaceMember(
                workspaceId,
                memberId,
                "MEMBER"
        );

        // when
        ResultActions result = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECORDING"));
        assertThat(recordingCount(memberId)).isEqualTo(2);
    }

    private long startedRecordingId(
            long workspaceId,
            long memberId,
            UUID requestId
    ) throws Exception {
        MvcResult result = startRecording(
                workspaceId,
                memberId,
                requestId
        ).andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
        return body.get("recordingId")
                .asLong();
    }

    private ResultActions startRecording(
            long workspaceId,
            long memberId,
            UUID requestId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        workspaceId
                ).cookie(
                        accessTokenCookie(memberId),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                        {"requestId":"%s","tabId":"%s","controlToken":"%s"}
                                        """.formatted(
                                        requestId,
                                        UUID.nameUUIDFromBytes(
                                                requestId.toString()
                                                        .getBytes()
                                        ),
                                        CONTROL_TOKEN
                                )
                        )
        );
    }

    private ResultActions leave(
            long workspaceId,
            long memberId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                delete(
                        "/api/v1/workspaces/{workspaceId}/members/me",
                        workspaceId
                ).cookie(
                        accessTokenCookie(memberId),
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
        JsonNode body = objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
        return new CsrfCredentials(
                cookie,
                body.get("token")
                        .asText()
        );
    }

    private Cookie accessTokenCookie(long memberId) {
        return new Cookie(
                JWT_COOKIE_NAME,
                authTokenProvider.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "octocat",
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

    private long saveWorkspace() {
        return jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES ('녹음 폐기 팀', CAST(:createdAt AS TIMESTAMPTZ))
                RETURNING id
                """)
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
                        "role",
                        role
                )
                .param(
                        "joinedAt",
                        JOINED_AT.toString()
                )
                .update();
    }

    private String recordingStatus(long recordingId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private long recordingCount(long memberId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM recording_sessions WHERE member_id = :memberId")
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .single();
    }

    private boolean workspaceDeleted(long workspaceId) {
        return jdbcClient.sql("SELECT deleted_at IS NOT NULL FROM workspaces WHERE id = :workspaceId")
                .param(
                        "workspaceId",
                        workspaceId
                )
                .query(Boolean.class)
                .single();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
