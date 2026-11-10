package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class RecordingCurrentAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    RecordingCurrentAcceptanceTest(
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
    @DisplayName("다른 탭에서 CSRF 없이 조회해도 200과 진행 중인 녹음을 반환하고 탭 ID와 제어 비밀값은 반환하지 않는다")
    void findCurrent_success_recording() throws Exception {
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
        ResultActions result = findCurrent(
                workspaceId,
                memberId
        );

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recording.id()))
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.startedAt").value(recording.startedAt()))
                .andExpect(jsonPath("$.elapsedMillis").isNumber())
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(
                CONTROL_TOKEN,
                recording.tabId()
                        .toString()
        );
    }

    @Test
    @DisplayName("일시정지한 녹음을 조회하면 PAUSED와 일시정지 시점의 누적 시간을 반환한다")
    void findCurrent_success_paused() throws Exception {
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
        JsonNode paused = readBody(
                pause(
                        workspaceId,
                        recording,
                        memberId
                ).andExpect(status().isOk())
                        .andReturn()
        );

        // when
        ResultActions result = findCurrent(
                workspaceId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(
                        jsonPath("$.elapsedMillis").value(
                                paused.get("elapsedMillis")
                                        .asLong()
                        )
                );
    }

    @Test
    @DisplayName("녹음 탭의 마지막 생존 뒤 30초가 지났으면 DISCONNECTED를 반환하고 조회는 마지막 생존 시각을 늘리지 않는다")
    void findCurrent_success_disconnected() throws Exception {
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
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - INTERVAL '31 seconds',
                    current_interval_started_at = current_interval_started_at - INTERVAL '31 seconds',
                    last_seen_at = last_seen_at - INTERVAL '31 seconds'
                WHERE id = :recordingId
                """)
                .param(
                        "recordingId",
                        recording.id()
                )
                .update();
        String lastSeenAt = lastSeenAt(recording.id());

        // when
        ResultActions first = findCurrent(
                workspaceId,
                memberId
        );
        ResultActions second = findCurrent(
                workspaceId,
                memberId
        );

        // then
        first.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.connectionStatus").value("DISCONNECTED"));
        second.andExpect(jsonPath("$.connectionStatus").value("DISCONNECTED"));
        assertThat(lastSeenAt(recording.id())).isEqualTo(lastSeenAt);
    }

    @Test
    @DisplayName("요청 Workspace에 본인 녹음이 없으면 본문 없는 204를 반환한다")
    void findCurrent_success_noRecording() throws Exception {
        // given
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = findCurrent(
                workspaceId,
                memberId
        );

        // then
        result.andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("종료한 녹음은 업로드와 문서 처리가 끝날 때까지 문서 생성 중으로 반환한다")
    void findCurrent_success_endedRecordingProcessing() throws Exception {
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
        ResultActions result = findCurrent(
                workspaceId,
                memberId
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recording.id()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.connectionStatus").isEmpty())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("문서 생성 중이던 최신 녹음이 완료되면 다음 조회에서 본문 없는 204를 반환한다")
    void findCurrent_success_completedProcessingDisappears() throws Exception {
        // given
        long memberId = saveMember("completed-member");
        long workspaceId = saveWorkspace("문서 팀");
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
        findCurrent(
                workspaceId,
                memberId
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        saveSucceededBatch(recording.id());

        // when
        ResultActions result = findCurrent(
                workspaceId,
                memberId
        );

        // then
        result.andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("다른 Workspace에서 진행 중인 본인 녹음은 반환하지 않고 204를 반환한다")
    void findCurrent_success_otherWorkspaceRecordingExcluded() throws Exception {
        // given
        long memberId = saveMember("member");
        long recordingWorkspaceId = saveWorkspace("녹음 팀");
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                recordingWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                otherWorkspaceId,
                memberId
        );
        startRecording(
                recordingWorkspaceId,
                memberId
        );

        // when
        ResultActions result = findCurrent(
                otherWorkspaceId,
                memberId
        );

        // then
        result.andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버 녹음은 반환하지 않고 204를 반환한다")
    void findCurrent_success_otherMemberRecordingExcluded() throws Exception {
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
        startRecording(
                workspaceId,
                starterId
        );

        // when
        ResultActions result = findCurrent(
                workspaceId,
                otherId
        );

        // then
        result.andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Workspace 소속이 아니면 WORKSPACE_ACCESS_DENIED 403을 반환한다")
    void findCurrent_failure_notWorkspaceMember() throws Exception {
        // given
        long memberId = saveMember("member");
        long outsiderId = saveMember("outsider");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        startRecording(
                workspaceId,
                memberId
        );

        // when
        ResultActions result = findCurrent(
                workspaceId,
                outsiderId
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("없는 Workspace면 WORKSPACE_NOT_FOUND 404를 반환한다")
    void findCurrent_failure_workspaceNotFound() throws Exception {
        // given
        long memberId = saveMember("member");

        // when
        ResultActions result = findCurrent(
                999L,
                memberId
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 조회하면 401을 반환한다")
    void findCurrent_failure_unauthenticated() throws Exception {
        // given
        String path = "/api/v1/workspaces/1/recordings/current";

        // when
        ResultActions result = mockMvc.perform(get(path));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 내 현재 녹음 조회 계약을 공개한다")
    void openApi_success_recordingCurrentContract() throws Exception {
        // given
        String currentPath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings/current'].get";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(currentPath + ".summary").value("내 현재 녹음 조회"))
                .andExpect(
                        jsonPath(currentPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingCurrentResponse")
                )
                .andExpect(jsonPath(currentPath + ".responses['204']").exists())
                .andExpect(jsonPath(currentPath + ".responses['204'].content").doesNotExist())
                .andExpect(
                        jsonPath(currentPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(currentPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(jsonPath("$.components.schemas.RecordingCurrentResponse.properties.length()").value(7))
                .andExpect(
                        jsonPath("$.components.schemas.RecordingCurrentResponse.properties.status.enum").value(
                                org.hamcrest.Matchers.contains(
                                        "RECORDING",
                                        "PAUSED",
                                        "PROCESSING",
                                        "TRANSCRIPTION_FAILED",
                                        "DOCUMENT_FAILED"
                                )
                        )
                )
                .andExpect(jsonPath(currentPath + ".security[*].accessTokenCookie").exists())
                .andExpect(jsonPath(currentPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')]").isEmpty());
    }

    private String lastSeenAt(long recordingId) {
        return jdbcClient.sql("SELECT last_seen_at::text FROM recording_sessions WHERE id = :recordingId")
                .param(
                        "recordingId",
                        recordingId
                )
                .query(String.class)
                .single();
    }

    private void saveSucceededBatch(long recordingId) {
        long transcriptId = jdbcClient.sql("""
                INSERT INTO transcripts (recording_session_id, content, created_at)
                VALUES (:recordingId, '회의 내용', CURRENT_TIMESTAMP)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO document_generation_batches
                    (recording_session_id, transcript_id, topic_registration_state,
                        accepted_at, registered_at, processing_status, finished_at)
                VALUES (:recordingId, :transcriptId, 'TOPICS_REGISTERED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SUCCEEDED', CURRENT_TIMESTAMP)
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "transcriptId",
                        transcriptId
                )
                .update();
    }

    private ResultActions findCurrent(
            long workspaceId,
            long memberId
    ) throws Exception {
        return mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/recordings/current",
                        workspaceId
                ).cookie(accessTokenCookie(memberId))
        );
    }

    private ResultActions pause(
            long workspaceId,
            StartedRecording recording,
            long memberId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post("/api/v1/workspaces/" + workspaceId + "/recordings/" + recording.id() + "/pause")
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
                                        recording.tabId(),
                                        CONTROL_TOKEN
                                )
                        )
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
        JsonNode body = readBody(result);
        return new StartedRecording(
                body.get("recordingId")
                        .asLong(),
                tabId,
                body.get("startedAt")
                        .asText()
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

    private record StartedRecording(
            long id,
            UUID tabId,
            String startedAt
    ) {
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
