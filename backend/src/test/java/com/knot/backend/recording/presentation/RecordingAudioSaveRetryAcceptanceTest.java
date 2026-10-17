package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@TestPropertySource(properties = "recording.audio-chunks.enabled=true")
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingAudioSaveRetryAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String RECORDING_PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}";
    private static final String CHUNKS_PATH = RECORDING_PATH + "/audio-chunks";
    private static final String COMPLETE_PATH = CHUNKS_PATH + "/{sequence}/complete";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private com.knot.backend.recording.application.RecordingAudioFinalizationWorker worker;

    RecordingAudioSaveRetryAcceptanceTest(
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
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_members, workspaces,
                    oauth_identities, members RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    void retry_returns202Then200ForSameJob() throws Exception {
        // given
        Fixture fixture = failedRecording();

        // when
        ResultActions result = retry(fixture);

        // then
        result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(1));
        retry(fixture).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(1));
    }

    @Test
    void retry_withoutCsrfIsForbidden() throws Exception {
        // given
        Fixture fixture = failedRecording();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        RECORDING_PATH + "/audio-save/retry",
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(accessTokenCookie(fixture.memberId()))
        );

        // then
        result.andExpect(status().isForbidden());
        assertThat(
                jdbcClient.sql("SELECT status FROM recording_audio_finalizations")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    @Test
    void retry_withoutAuthenticationIsUnauthorized() throws Exception {
        // given
        Fixture fixture = failedRecording();
        CsrfCredentials csrf = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        RECORDING_PATH + "/audio-save/retry",
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(csrf.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
        );

        // then
        result.andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1, 0})
    void readState_stopsRetryAtExactRetentionCutoff(long offsetMicros) throws Exception {
        // given
        Fixture fixture = failedRecording();
        moveToRetentionBoundary(
                fixture,
                offsetMicros
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        RECORDING_PATH + "/audio-save",
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(accessTokenCookie(fixture.memberId()))
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.audioSaveStatus").value("FAILED"))
                .andExpect(jsonPath("$.canRetry").value(offsetMicros < 0));
        assertThat(
                jdbcClient.sql("SELECT status FROM recording_audio_finalizations")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1, 0})
    void retry_rejectsAtExactRetentionCutoff(long offsetMicros) throws Exception {
        // given
        Fixture fixture = failedRecording();
        moveToRetentionBoundary(
                fixture,
                offsetMicros
        );

        // when
        ResultActions result = retry(fixture);

        // then
        result.andExpect(status().is(retryBoundaryStatus(offsetMicros)));
        assertThat(
                jdbcClient.sql("SELECT status FROM recording_audio_finalizations")
                        .query(String.class)
                        .single()
        ).isEqualTo(retryBoundaryJobStatus(offsetMicros));
    }

    private void moveToRetentionBoundary(
            Fixture fixture,
            long offsetMicros
    ) {
        jdbcClient.sql("UPDATE recording_sessions SET ended_at=CAST(:time AS TIMESTAMPTZ) WHERE id=:id")
                .param(
                        "time",
                        NOW.toString()
                )
                .param(
                        "id",
                        fixture.recordingId()
                )
                .update();
        when(clock.instant()).thenReturn(
                NOW.plus(java.time.Duration.ofDays(30))
                        .plus(
                                offsetMicros,
                                java.time.temporal.ChronoUnit.MICROS
                        )
        );
    }

    private int retryBoundaryStatus(long offsetMicros) {
        if (offsetMicros < 0) {
            return 202;
        }
        return 409;
    }

    private String retryBoundaryJobStatus(long offsetMicros) {
        if (offsetMicros < 0) {
            return "QUEUED";
        }
        return "FAILED";
    }

    @Test
    @DisplayName("OpenAPI는 저장 재접수의 두 성공 응답과 인증·CSRF·오류 계약을 공개한다")
    void openApi_success_audioSaveRetryContract() throws Exception {
        // given
        String operation = "$.paths['" + RECORDING_PATH + "/audio-save/retry'].post";
        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));
        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(operation + ".responses['202'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioSaveResponse")
                )
                .andExpect(
                        jsonPath(operation + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioSaveResponse")
                )
                .andExpect(
                        jsonPath(operation + ".responses['401'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse")
                )
                .andExpect(
                        jsonPath(operation + ".responses['403'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse")
                )
                .andExpect(
                        jsonPath(operation + ".responses['409'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse")
                )
                .andExpect(jsonPath(operation + ".security[0].accessTokenCookie").isArray())
                .andExpect(
                        jsonPath(operation + ".parameters[?(@.name == 'X-XSRF-TOKEN' && @.in == 'header')].required")
                                .value(org.hamcrest.Matchers.contains(true))
                )
                .andExpect(jsonPath(operation + ".requestBody").doesNotExist());
    }

    private ResultActions retry(Fixture fixture) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
                post(
                        RECORDING_PATH + "/audio-save/retry",
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
        );
    }

    private Fixture failedRecording() throws Exception {
        Fixture fixture = startedRecording();
        jdbcClient.sql("""
                            UPDATE recording_sessions SET status='ENDED',
                ended_at=GREATEST(now(),started_at,last_seen_at), end_reason='USER_ENDED',
                                current_interval_started_at=NULL WHERE id=:id
                            """)
                .param(
                        "id",
                        fixture.recordingId()
                )
                .update();
        jdbcClient.sql("""
                INSERT INTO recording_audio_finalizations(recording_id,status,created_at,next_run_at,
                    claim_epoch,attempt,failure_count,failure_code,retryable)
                VALUES (:id,'FAILED',now(),now(),0,4,4,'AUDIO_STORAGE_UNAVAILABLE',TRUE)
                """)
                .param(
                        "id",
                        fixture.recordingId()
                )
                .update();
        return fixture;
    }

    private Fixture startedRecording() throws Exception {
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
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
                                        {"requestId":"%s","tabId":"%s",
                                         "controlToken":"%s","audioMode":"PCM16_CHUNKS_V1"}
                                        """.formatted(
                                        UUID.randomUUID(),
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        )
                .andExpect(status().isCreated())
                .andReturn();
        return new Fixture(
                workspaceId,
                memberId,
                readBody(result).get("recordingId")
                        .asLong()
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

    private record Fixture(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
