package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioUpload;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.StoredAudioObject;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
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
class RecordingAudioUploadCompletionAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String RECORDING_PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}";
    private static final String COMPLETE_PATH = RECORDING_PATH + "/audio-upload-complete";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final Clock clock;
    @MockitoBean
    private RecordingAudioStorage recordingAudioStorage;

    RecordingAudioUploadCompletionAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient,
            Clock clock
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
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

    @BeforeEach
    void stubPresign() {
        when(
                recordingAudioStorage.presignUpload(
                        anyString(),
                        anyString(),
                        anyLong()
                )
        ).thenReturn(
                new PresignedAudioUpload(
                        "https://storage.example/upload",
                        clock.instant()
                                .plusSeconds(3600)
                )
        );
    }

    @Test
    @DisplayName("PUT한 파일이 예약과 같으면 200과 COMPLETED를 반환하고 재호출에도 같은 완료 시각을 반환한다")
    void complete_success_uploadedObject() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        stubStoredObject(1024L);
        String firstCompletedAt = readBody(
                complete(
                        fixture,
                        fixture.uploadId()
                ).andExpect(status().isOk())
                        .andReturn()
        ).get("completedAt")
                .asText();

        // when
        ResultActions result = complete(
                fixture,
                fixture.uploadId()
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(fixture.recordingId()))
                .andExpect(jsonPath("$.uploadId").value(fixture.uploadId()))
                .andExpect(jsonPath("$.uploadStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").value(firstCompletedAt));
    }

    @Test
    @DisplayName("PUT 전에 완료를 요청하면 409다")
    void complete_failure_notUploaded() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        when(recordingAudioStorage.findStoredObject(anyString())).thenReturn(StoredAudioObject.missing());

        // when
        ResultActions result = complete(
                fixture,
                fixture.uploadId()
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_UPLOAD_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("올라간 파일 크기가 예약과 다르면 409다")
    void complete_failure_lengthMismatch() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        stubStoredObject(999L);

        // when
        ResultActions result = complete(
                fixture,
                fixture.uploadId()
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_UPLOAD_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("없는 uploadId로 완료하면 404다")
    void complete_failure_unknownUpload() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();

        // when
        ResultActions result = complete(
                fixture,
                fixture.uploadId() + 100
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIO_UPLOAD_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버가 완료하면 403이다")
    void complete_failure_otherMember() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        long otherId = saveMember("other");
        saveWorkspaceMember(
                fixture.workspaceId(),
                otherId
        );
        stubStoredObject(1024L);

        // when
        ResultActions result = mockMvc.perform(
                completeRequest(
                        fixture.workspaceId(),
                        fixture.recordingId(),
                        otherId,
                        fixture.uploadId()
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("저장소 장애면 500이다")
    void complete_failure_storageUnavailable() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        when(recordingAudioStorage.findStoredObject(anyString()))
                .thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        ResultActions result = complete(
                fixture,
                fixture.uploadId()
        );

        // then
        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIO_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("uploadId가 없으면 VALIDATION_ERROR 400이다")
    void complete_failure_missingUploadId() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        COMPLETE_PATH,
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(
                        accessTokenCookie(fixture.memberId()),
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("인증됐어도 CSRF 토큰이 없으면 403이다")
    void complete_failure_missingCsrf() throws Exception {
        // given
        UploadFixture fixture = reservedUpload();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        COMPLETE_PATH,
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(accessTokenCookie(fixture.memberId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(fixture.uploadId()))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 업로드 완료 확인 계약을 공개한다")
    void openApi_success_audioUploadCompleteContract() throws Exception {
        // given
        String path = "$.paths['" + COMPLETE_PATH + "'].post";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").value("최종 오디오 업로드 완료 확인"))
                .andExpect(
                        jsonPath(path + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioUploadCompletionResponse")
                )
                .andExpect(
                        jsonPath(path + ".responses['409'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse")
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingAudioUploadCompletionRequest.required")
                                .value(hasItems("uploadId"))
                )
                .andExpect(jsonPath(path + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true)));
    }

    private ResultActions complete(
            UploadFixture fixture,
            long uploadId
    ) throws Exception {
        return mockMvc.perform(
                completeRequest(
                        fixture.workspaceId(),
                        fixture.recordingId(),
                        fixture.memberId(),
                        uploadId
                )
        );
    }

    private RequestBuilder completeRequest(
            long workspaceId,
            long recordingId,
            long memberId,
            long uploadId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return post(
                COMPLETE_PATH,
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(completeBody(uploadId));
    }

    private String completeBody(long uploadId) {
        return """
                {"uploadId":%d}
                """.formatted(uploadId);
    }

    private UploadFixture reservedUpload() throws Exception {
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
        return new UploadFixture(
                workspaceId,
                memberId,
                recordingId,
                uploadId
        );
    }

    private void stubStoredObject(long contentLength) {
        when(recordingAudioStorage.findStoredObject(anyString())).thenReturn(
                StoredAudioObject.of(
                        contentLength,
                        "audio/webm",
                        "version-1"
                )
        );
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

    private record UploadFixture(
            long workspaceId,
            long memberId,
            long recordingId,
            long uploadId
    ) {
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
