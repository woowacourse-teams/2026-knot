package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
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
class RecordingAudioUploadUrlAcceptanceTest {
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

    RecordingAudioUploadUrlAcceptanceTest(
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
                TRUNCATE TABLE recording_audio_uploads, recording_sessions, workspace_invitations,
                    workspace_members, workspaces, oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("종료한 녹음의 업로드 URL을 요청하면 201과 uploadId, 서명된 PUT URL, 만료 시각을 반환한다")
    void issue_success_endedRecording() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                1024L
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").isNumber())
                .andExpect(
                        jsonPath("$.uploadUrl").value(
                                startsWith("https://knot-test-audio.s3.ap-northeast-2.amazonaws.com/recordings/")
                        )
                )
                .andExpect(jsonPath("$.uploadUrl").value(containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("완료되지 않은 예약을 다시 요청하면 200과 같은 uploadId로 URL을 재발급한다")
    void issue_success_reissue() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );
        long uploadId = readBody(
                issue(
                        workspaceId,
                        Long.toString(recordingId),
                        memberId,
                        "audio/webm",
                        1024L
                ).andExpect(status().isCreated())
                        .andReturn()
        ).get("uploadId")
                .asLong();

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                1024L
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId));
    }

    @Test
    @DisplayName("종료 전 녹음의 업로드 URL 요청은 409다")
    void issue_failure_notEnded() throws Exception {
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
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                1024L
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_ENDED"));
    }

    @Test
    @DisplayName("업로드가 완료된 녹음의 업로드 URL 요청은 409다")
    void issue_failure_alreadyCompleted() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );
        issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                1024L
        ).andExpect(status().isCreated());
        jdbcClient.sql("""
                UPDATE recording_audio_uploads SET status = 'COMPLETED', completed_at = reserved_at
                WHERE recording_id = :recordingId
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .update();

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                1024L
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_UPLOAD_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버가 요청하면 403이다")
    void issue_failure_otherMember() throws Exception {
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
        long recordingId = endedRecording(
                workspaceId,
                starterId
        );

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                otherId,
                "audio/webm",
                1024L
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("허용되지 않은 형식이나 최대 크기를 넘는 파일은 400이다")
    void issue_failure_disallowedFile() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/mpeg",
                1024L
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_UPLOAD"));
    }

    @Test
    @DisplayName("크기가 없거나 0 이하이면 VALIDATION_ERROR 400이다")
    void issue_failure_invalidContentLength() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = issue(
                workspaceId,
                Long.toString(recordingId),
                memberId,
                "audio/webm",
                0L
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 403이다")
    void issue_failure_missingCsrf() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        long recordingId = endedRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url",
                        workspaceId,
                        recordingId
                ).cookie(accessTokenCookie(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                body(
                                        "audio/webm",
                                        1024L
                                )
                        )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 업로드 URL 발급 계약을 공개한다")
    void openApi_success_audioUploadUrlContract() throws Exception {
        // given
        String path = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url'].post";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").value("최종 오디오 업로드 URL 발급"))
                .andExpect(
                        jsonPath(path + ".responses['201'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioUploadUrlResponse")
                )
                .andExpect(
                        jsonPath(path + ".responses['409'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse")
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingAudioUploadUrlRequest.required").value(
                                hasItems(
                                        "contentType",
                                        "contentLength"
                                )
                        )
                )
                .andExpect(jsonPath(path + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true)));
    }

    private ResultActions issue(
            long workspaceId,
            String recordingId,
            long memberId,
            String contentType,
            long contentLength
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/recordings/" + recordingId + "/audio-upload-url")
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
                                body(
                                        contentType,
                                        contentLength
                                )
                        )
        );
    }

    private String body(
            String contentType,
            long contentLength
    ) {
        return """
                {"contentType":"%s","contentLength":%d}
                """.formatted(
                contentType,
                contentLength
        );
    }

    private long endedRecording(
            long workspaceId,
            long memberId
    ) throws Exception {
        long recordingId = startRecording(
                workspaceId,
                memberId
        );
        end(
                workspaceId,
                Long.toString(recordingId),
                memberId
        ).andExpect(status().isOk());
        return recordingId;
    }

    private ResultActions end(
            long workspaceId,
            String recordingId,
            long memberId
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
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
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

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
