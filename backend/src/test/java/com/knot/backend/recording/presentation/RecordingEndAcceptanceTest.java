package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingEndAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String OTHER_CONTROL_TOKEN = "ZYXWVUTSRQPONMLKJIHGFEDCBA9876543210zyxwvuA";
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    RecordingEndAcceptanceTest(
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
    @DisplayName("최초 탭이 종료하면 200과 ENDED, 서버 종료 시각을 반환하고 제어 비밀값은 반환하지 않는다")
    void end_success_starter() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(CONTROL_TOKEN);
        assertThat(recordingStatus(recordingId)).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("최초 탭이 이미 종료된 녹음을 다시 종료하면 처음 종료 시각을 그대로 반환한다")
    void end_success_repeatedEndKeepsEndedAt() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );
        String firstEndedAt = readBody(
                end(
                        workspaceId,
                        Long.toString(recordingId),
                        memberId
                ).andExpect(status().isOk())
                        .andReturn()
        ).get("endedAt")
                .asText();

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").value(firstEndedAt));
    }

    @Test
    @DisplayName("같은 회원의 다른 탭이 종료하면 403이고 녹음을 유지한다")
    void end_failure_otherTab() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                controlBody(
                        UUID.randomUUID(),
                        CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("최초 탭 ID라도 제어 증명이 다르면 403이고 녹음을 유지한다")
    void end_failure_wrongControlToken() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                controlBody(
                        TAB_ID,
                        OTHER_CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("이미 종료된 녹음도 다른 탭의 재요청이면 종료 결과를 돌려주지 않고 403이다")
    void end_failure_repeatedEndFromOtherTab() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );
        end(
                workspaceId,
                Long.toString(recordingId),
                memberId
        ).andExpect(status().isOk());

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                controlBody(
                        UUID.randomUUID(),
                        CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"))
                .andExpect(jsonPath("$.endedAt").doesNotExist());
        assertThat(recordingStatus(recordingId)).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("요청 본문 없이 본인 녹음을 원격 종료한다")
    void end_success_bodylessOwnerRemoteEnd() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        workspaceId,
                        recordingId
                ).cookie(
                        accessTokenCookie(memberId),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.audioSave").doesNotExist());
        assertThat(recordingStatus(recordingId)).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버가 종료하면 403이고 녹음을 유지한다")
    void end_failure_otherMember() throws Exception {
        // given
        long starterId = saveMember("starter");
        long otherId = saveMember("other");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                starterId
        );
        saveWorkspaceMember(
                workspaceId,
                otherId
        );
        long recordingId = startRecording(
                workspaceId,
                starterId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                otherId
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("Workspace 멤버가 아니면 403이다")
    void end_failure_nonMember() throws Exception {
        // given
        long starterId = saveMember("starter");
        long outsiderId = saveMember("outsider");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                starterId
        );
        long recordingId = startRecording(
                workspaceId,
                starterId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                outsiderId
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("다른 Workspace의 녹음 ID로 종료하면 404이고 녹음을 유지한다")
    void end_failure_recordingInOtherWorkspace() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("녹음 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                otherWorkspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("없는 녹음 ID로 종료하면 404다")
    void end_failure_recordingNotFound() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                "999",
                memberId
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("탈퇴로 폐기된 녹음은 재가입 뒤에도 종료할 수 없어 409다")
    void end_failure_discardedRecording() throws Exception {
        // given
        long ownerId = saveMember("owner");
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                ownerId
        );
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );
        leave(
                workspaceId,
                memberId
        ).andExpect(status().isNoContent());
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ALREADY_DISCARDED"));
        assertThat(recordingStatus(recordingId)).isEqualTo("DISCARDED");
    }

    @Test
    @DisplayName("양수가 아닌 녹음 ID는 400이다")
    void end_failure_nonPositiveRecordingId() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                "0",
                memberId
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECORDING_DATA"));
    }

    @Test
    @DisplayName("녹음 ID 형식이 잘못되면 내부 오류 없는 400이다")
    void end_failure_invalidRecordingIdFormat() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = end(
                workspaceId,
                "invalid",
                memberId
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("인증 없이 CSRF 토큰만 보내면 종료는 401이다")
    void end_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/recordings/1/end").cookie(csrf.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                controlBody(
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 종료는 403이고 녹음을 유지한다")
    void end_failure_missingCsrf() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        workspaceId,
                        recordingId
                ).cookie(accessTokenCookie(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                controlBody(
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("OpenAPI JSON에 녹음 종료 계약을 공개한다")
    void openApi_success_recordingEndContract() throws Exception {
        // given
        String endPath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end'].post";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(endPath + ".summary").value("녹음 종료"))
                .andExpect(
                        jsonPath(endPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingEndResponse")
                )
                .andExpect(
                        jsonPath(endPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(endPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(endPath + ".responses['409'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(jsonPath(endPath + ".requestBody").exists())
                .andExpect(
                        jsonPath(endPath + ".requestBody.content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingEndRequest")
                )
                .andExpect(jsonPath(endPath + ".security[*].accessTokenCookie").exists())
                .andExpect(
                        jsonPath(endPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true))
                );
    }

    private ResultActions end(
            long workspaceId,
            String recordingId,
            long memberId
    ) throws Exception {
        return end(
                workspaceId,
                recordingId,
                memberId,
                controlBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );
    }

    private ResultActions end(
            long workspaceId,
            String recordingId,
            long memberId,
            String body
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/recordings/" + recordingId + "/end")
                        .cookie(
                                accessTokenCookie(memberId),
                                csrf.cookie()
                        )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    private String controlBody(
            UUID tabId,
            String controlToken
    ) {
        return """
                {"tabId":"%s","controlToken":"%s"}
                """.formatted(
                tabId,
                controlToken
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

    private long startRecording(
            long workspaceId,
            long memberId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        MvcResult result = mockMvc.perform(
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
                                        UUID.randomUUID(),
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        )
                .andExpect(status().isCreated())
                .andReturn();
        return readBody(result).get("recordingId")
                .asLong();
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
    }

    private CsrfCredentials csrfCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(CSRF_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return new CsrfCredentials(
                cookie,
                readBody(result).get("token")
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

    private String recordingStatus(long recordingId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
