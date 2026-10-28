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
class RecordingHeartbeatAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    RecordingHeartbeatAcceptanceTest(
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
    @DisplayName("최초 탭이 신호를 보내면 200과 RECORDING, 마지막 신호와 만료 예정 시각을 반환하고 제어 비밀값은 반환하지 않는다")
    void heartbeat_success_firstTab() throws Exception {
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
        ResultActions result = heartbeat(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recording.id()))
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.lastSeenAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.serverNow").isNotEmpty())
                .andExpect(jsonPath("$.elapsedMillis").isNumber())
                .andExpect(jsonPath("$.endedAt").isEmpty())
                .andExpect(jsonPath("$.endReason").isEmpty())
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(CONTROL_TOKEN);
        assertThat(recordingStatus(recording.id())).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("마지막 생존 뒤 30분이 지난 신호는 200과 마지막 생존 시각으로 끝난 연결 만료 상태를 반환하고 녹음을 되살리지 않는다")
    void heartbeat_success_expiredRecording() throws Exception {
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
        shiftBack(
                recording.id(),
                35
        );
        String lastSeenAt = lastSeenAt(recording.id());

        // when
        ResultActions result = heartbeat(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("CONNECTION_EXPIRED"))
                .andExpect(jsonPath("$.endedAt").value(lastSeenAt))
                .andExpect(jsonPath("$.lastSeenAt").value(lastSeenAt))
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.connectionStatus").isEmpty());
        assertThat(recordingStatus(recording.id())).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("끊김 유예 안에서 다시 온 신호는 녹음을 이어 가고 만료 예정 시각을 마지막 생존 뒤 30분으로 늘린다")
    void heartbeat_success_returnsWithinGrace() throws Exception {
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
        shiftBack(
                recording.id(),
                29
        );

        // when
        ResultActions result = heartbeat(
                workspaceId,
                Long.toString(recording.id()),
                memberId,
                recording.tabId()
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        Instant lastSeen = Instant.parse(
                json.get("lastSeenAt")
                        .asText()
        );
        assertThat(
                Instant.parse(
                        json.get("expiresAt")
                                .asText()
                )
        ).isEqualTo(lastSeen.plusSeconds(1_800));
        assertThat(
                json.get("elapsedMillis")
                        .asLong()
        ).isGreaterThanOrEqualTo(29 * 60 * 1000L);
    }

    @Test
    @DisplayName("같은 회원의 다른 탭이 신호를 보내면 403이고 녹음을 유지한다")
    void heartbeat_failure_otherTab() throws Exception {
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
        ResultActions result = heartbeat(
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
    @DisplayName("같은 Workspace의 다른 멤버가 신호를 보내면 403이다")
    void heartbeat_failure_otherMember() throws Exception {
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
        ResultActions result = heartbeat(
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
    @DisplayName("제어 증명이 없으면 VALIDATION_ERROR 400이다")
    void heartbeat_failure_missingControlToken() throws Exception {
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
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/heartbeat",
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
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 생존 신호는 403이고 녹음을 유지한다")
    void heartbeat_failure_missingCsrf() throws Exception {
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
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/heartbeat",
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
    @DisplayName("인증 없이 CSRF 토큰만 보내면 생존 신호는 401이다")
    void heartbeat_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/workspaces/1/recordings/1/heartbeat").cookie(csrf.cookie())
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
    @DisplayName("OpenAPI JSON에 녹음 생존 신호 계약을 공개한다")
    void openApi_success_recordingHeartbeatContract() throws Exception {
        // given
        String heartbeatPath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/heartbeat'].post";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(heartbeatPath + ".summary").value("녹음 생존 신호"))
                .andExpect(
                        jsonPath(heartbeatPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingHeartbeatResponse")
                )
                .andExpect(
                        jsonPath(heartbeatPath + ".requestBody.content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingControlRequest")
                )
                .andExpect(
                        jsonPath(heartbeatPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(jsonPath(heartbeatPath + ".responses['409']").doesNotExist())
                .andExpect(
                        jsonPath("$.components.schemas.RecordingHeartbeatResponse.properties.connectionStatus.enum")
                                .value(
                                        hasItems(
                                                "CONNECTED",
                                                "DISCONNECTED"
                                        )
                                )
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingHeartbeatResponse.properties.endReason.enum").value(
                                hasItems(
                                        "USER_ENDED",
                                        "CONNECTION_EXPIRED"
                                )
                        )
                )
                .andExpect(jsonPath(heartbeatPath + ".security[*].accessTokenCookie").exists())
                .andExpect(
                        jsonPath(heartbeatPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required")
                                .value(hasItems(true))
                );
    }

    private ResultActions heartbeat(
            long workspaceId,
            String recordingId,
            long memberId,
            UUID tabId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/recordings/" + recordingId + "/heartbeat")
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

    // 실제로 기다리지 않도록 녹음의 모든 시각을 같은 만큼 과거로 옮겨 신호가 끊긴 상태를 만든다
    private void shiftBack(
            long recordingId,
            int minutes
    ) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - make_interval(mins => :minutes),
                    current_interval_started_at = current_interval_started_at - make_interval(mins => :minutes),
                    last_seen_at = last_seen_at - make_interval(mins => :minutes)
                WHERE id = :recordingId
                """)
                .param(
                        "minutes",
                        minutes
                )
                .param(
                        "recordingId",
                        recordingId
                )
                .update();
    }

    private String lastSeenAt(long recordingId) {
        return jdbcClient.sql("SELECT last_seen_at FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Instant.class)
                .single()
                .toString();
    }

    private String controlBody(UUID tabId) {
        return """
                {"tabId":"%s","controlToken":"%s"}
                """.formatted(
                tabId,
                CONTROL_TOKEN
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
