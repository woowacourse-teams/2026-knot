package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
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
class RecordingPauseAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    RecordingPauseAcceptanceTest(
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
    @DisplayName("최초 탭이 일시정지하면 200과 PAUSED, 일시정지 시각, 누적 시간을 반환하고 제어 비밀값은 반환하지 않는다")
    void pause_success_firstTab() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = pause(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recording.id()))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.pausedAt").isNotEmpty())
                .andExpect(jsonPath("$.elapsedMillis").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(CONTROL_TOKEN);
        assertThat(recordingStatus(recording.id())).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("이미 일시정지된 녹음을 다시 일시정지하면 처음 일시정지 시각과 누적 시간을 그대로 반환한다")
    void pause_success_repeatedPauseKeepsResult() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        JsonNode first = readBody(
                pause(
                        workspaceId,
                        Long.toString(recording.id()),
                        memberId,
                        recording.tabId()
                ).andExpect(status().isOk())
                        .andReturn()
        );

        // when
        ResultActions result = pause(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.pausedAt").value(
                                first.get("pausedAt")
                                        .asText()
                        )
                )
                .andExpect(
                        jsonPath("$.elapsedMillis").value(
                                first.get("elapsedMillis")
                                        .asLong()
                        )
                );
    }

    @Test
    @DisplayName("같은 회원의 다른 탭이 일시정지하면 403이고 녹음을 유지한다")
    void pause_failure_otherTab() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = pause(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                UUID.randomUUID()
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(recordingStatus(recording.id())).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버가 일시정지하면 403이다")
    void pause_failure_otherMember() throws Exception {
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
        StartedRecording recording = startRecording(
                workspaceId,
                starterId
        );

        // when
        ResultActions result = pause(
                workspaceId,
                Long.toString(recording.id()),
                otherId,
                recording.tabId()
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(recordingStatus(recording.id())).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("다른 Workspace의 녹음 ID로 일시정지하면 404다")
    void pause_failure_recordingInOtherWorkspace() throws Exception {
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
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = pause(
                otherWorkspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("종료된 녹음은 일시정지로 되살리지 않고 409다")
    void pause_failure_endedRecording() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        end(
                workspaceId,
                recording.id(),
                memberId,
                recording.tabId()
        ).andExpect(status().isOk());

        // when
        ResultActions result = pause(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ALREADY_ENDED"));
        assertThat(recordingStatus(recording.id())).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("제어 증명이 없으면 VALIDATION_ERROR 400이다")
    void pause_failure_missingControlToken() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/pause",
                        workspaceId,
                        recording.id()
                ).cookie(
                        accessTokenCookie(memberId),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tabId":"%s"}
                                """.formatted(recording.tabId()))
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(recordingStatus(recording.id())).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 일시정지는 403이고 녹음을 유지한다")
    void pause_failure_missingCsrf() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        StartedRecording recording = startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/pause",
                        workspaceId,
                        recording.id()
                ).cookie(accessTokenCookie(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlBody(recording.tabId()))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(recordingStatus(recording.id())).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("인증 없이 CSRF 토큰만 보내면 일시정지는 401이다")
    void pause_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/recordings/1/pause").cookie(csrf.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlBody(UUID.randomUUID()))
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 녹음 일시정지 계약을 공개한다")
    void openApi_success_recordingPauseContract() throws Exception {
        // given
        String pausePath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/pause'].post";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(pausePath + ".summary").value("녹음 일시정지"))
                .andExpect(
                        jsonPath(pausePath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingPauseResponse")
                )
                .andExpect(
                        jsonPath(pausePath + ".requestBody.content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingControlRequest")
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingControlRequest.required").value(
                                hasItems(
                                        "tabId",
                                        "controlToken"
                                )
                        )
                )
                .andExpect(
                        jsonPath(pausePath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(pausePath + ".responses['409'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(jsonPath(pausePath + ".security[*].accessTokenCookie").exists())
                .andExpect(
                        jsonPath(pausePath + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true))
                );
    }

    private ResultActions pause(
            long workspaceId,
            String recordingId,
            long memberId,
            UUID tabId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/recordings/" + recordingId + "/pause")
                        .cookie(
                                accessTokenCookie(memberId),
                                csrf.cookie()
                        )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlBody(tabId))
        );
    }

    private String controlBody(UUID tabId) {
        return """
                {"tabId":"%s","controlToken":"%s"}
                """.formatted(
                tabId,
                CONTROL_TOKEN
        );
    }

    private ResultActions end(
            long workspaceId,
            long recordingId,
            long memberId,
            UUID tabId
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
                        .content(
                                """
                                        {"tabId":"%s","controlToken":"%s"}
                                        """.formatted(
                                        tabId,
                                        CONTROL_TOKEN
                                )
                        )
        );
    }

    private StartedRecording startRecording(
            long workspaceId,
            long memberId
    ) throws Exception {
        UUID tabId = UUID.randomUUID();
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
                                        tabId,
                                        CONTROL_TOKEN
                                )
                        )
        )
                .andExpect(status().isCreated())
                .andReturn();
        return new StartedRecording(
                readBody(result).get("recordingId")
                        .asLong(),
                tabId
        );
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

    private record StartedRecording(
            long id,
            UUID tabId
    ) {
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
