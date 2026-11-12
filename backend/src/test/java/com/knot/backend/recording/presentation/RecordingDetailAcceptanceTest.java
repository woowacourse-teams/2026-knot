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
class RecordingDetailAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    RecordingDetailAcceptanceTest(
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
    @DisplayName("최초 탭이 아닌 화면도 본인 진행 중 녹음을 조회하면 200과 상태를 받고 제어 비밀값은 받지 않는다")
    void findDetail_success_activeRecording() throws Exception {
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
        ResultActions result = findDetail(
                workspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.endedAt").isEmpty())
                .andExpect(jsonPath("$.audioUploadStatus").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(CONTROL_TOKEN)
                .doesNotContain(TAB_ID.toString());
        assertThat(recordingStatus(recordingId)).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("종료한 녹음을 조회하면 현재 녹음 조회와 달리 200과 종료 시각·사유를 받는다")
    void findDetail_success_endedRecording() throws Exception {
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
        String endedAt = readBody(
                end(
                        workspaceId,
                        Long.toString(recordingId),
                        memberId
                ).andExpect(status().isOk())
                        .andReturn()
        ).get("endedAt")
                .asText();

        // when
        ResultActions result = findDetail(
                workspaceId,
                Long.toString(recordingId),
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").value(endedAt))
                .andExpect(jsonPath("$.endReason").value("USER_ENDED"));
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버 녹음을 조회하면 403이다")
    void findDetail_failure_otherMember() throws Exception {
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
        ResultActions result = findDetail(
                workspaceId,
                Long.toString(recordingId),
                otherId
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("없는 녹음 ID로 조회하면 404다")
    void findDetail_failure_notFound() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = findDetail(
                workspaceId,
                "999",
                memberId
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 조회하면 401이다")
    void findDetail_failure_unauthenticated() throws Exception {
        // given
        String path = "/api/v1/workspaces/1/recordings/1";

        // when
        ResultActions result = mockMvc.perform(get(path));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 녹음 단건 조회 계약을 공개한다")
    void openApi_success_recordingDetailContract() throws Exception {
        // given
        String detailPath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}'].get";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(detailPath + ".summary").value("내 녹음 단건 조회"))
                .andExpect(
                        jsonPath(detailPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingDetailResponse")
                )
                .andExpect(
                        jsonPath(detailPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(detailPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingDetailResponse.properties.audioUploadStatus.enum")
                                .value(
                                        hasItems(
                                                "RESERVED",
                                                "COMPLETED"
                                        )
                                )
                )
                .andExpect(jsonPath(detailPath + ".security[*].accessTokenCookie").exists());
    }

    private ResultActions findDetail(
            long workspaceId,
            String recordingId,
            long memberId
    ) throws Exception {
        return mockMvc.perform(
                get("/api/v1/workspaces/" + workspaceId + "/recordings/" + recordingId)
                        .cookie(accessTokenCookie(memberId))
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
