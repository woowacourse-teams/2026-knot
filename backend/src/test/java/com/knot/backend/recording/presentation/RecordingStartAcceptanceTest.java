package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingStartAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-01T00:01:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String OTHER_CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefE";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final RecordingSessionRepository recordingSessionRepository;
    private final TransactionTemplate transactionTemplate;

    RecordingStartAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider authTokenProvider,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient,
            RecordingSessionRepository recordingSessionRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.mockMvc = mockMvc;
        this.authTokenProvider = authTokenProvider;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
        this.recordingSessionRepository = recordingSessionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_sessions, workspace_members, workspaces,
                    oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
    }

    @Test
    @DisplayName("인증된 멤버가 녹음을 시작하면 201과 세션 정보를 반환한다")
    void start_success_created() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordingId").isNumber())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.tabId").doesNotExist())
                .andExpect(jsonPath("$.controlToken").doesNotExist());
        String responseBody = result.andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(responseBody).doesNotContain(CONTROL_TOKEN);
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 요청 키와 같은 입력을 재시도하면 200과 같은 세션 ID를 반환한다")
    void start_success_replaySameRequest() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        JsonNode firstBody = responseBody(
                startRecording(
                        workspaceId,
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN,
                        accessTokenCookie,
                        csrfCredentials
                ).andExpect(status().isCreated())
        );

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.recordingId").value(
                                firstBody.get("recordingId")
                                        .asLong()
                        )
                )
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(
                        jsonPath("$.startedAt").value(
                                firstBody.get("startedAt")
                                        .asText()
                        )
                );
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("종료된 세션을 재시도하면 새 세션 없이 ENDED를 반환한다")
    void start_success_replayEndedSession() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        long recordingId = responseBody(
                startRecording(
                        workspaceId,
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN,
                        accessTokenCookie,
                        csrfCredentials
                ).andExpect(status().isCreated())
        ).get("recordingId")
                .asLong();
        endRecording(recordingId);

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.status").value("ENDED"));
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 녹음 중 다른 요청 키로 시작하면 409를 반환한다")
    void start_failure_activeRecordingAlreadyExists() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long firstWorkspaceId = saveWorkspace("첫 팀");
        long secondWorkspaceId = saveWorkspace("두 번째 팀");
        saveWorkspaceMember(
                firstWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                secondWorkspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        startRecording(
                firstWorkspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        ).andExpect(status().isCreated());

        // when
        ResultActions result = startRecording(
                secondWorkspaceId,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                TAB_ID,
                OTHER_CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RECORDING_ALREADY_EXISTS"));
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 요청 키의 tabId가 달라지면 RECORDING_START_REQUEST_CONFLICT 409를 반환한다")
    void start_failure_sameRequestDifferentTabId() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        ).andExpect(status().isCreated());

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_START_REQUEST_CONFLICT"));
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 요청 키의 controlToken이 달라지면 409에서 비밀값을 숨긴다")
    void start_failure_sameRequestDifferentControlToken() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        ).andExpect(status().isCreated());

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                OTHER_CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_START_REQUEST_CONFLICT"));
        assertThat(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        ).doesNotContain(OTHER_CONTROL_TOKEN);
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 멤버는 같은 요청 키로도 별도 녹음을 시작할 수 있다")
    void start_success_otherMemberHasSeparateSession() throws Exception {
        // given
        long firstMemberId = saveMember("octocat");
        long secondMemberId = saveMember("second-member");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                firstMemberId
        );
        saveWorkspaceMember(
                workspaceId,
                secondMemberId
        );
        Cookie firstAccessTokenCookie = accessTokenCookie(firstMemberId);
        Cookie secondAccessTokenCookie = accessTokenCookie(secondMemberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        long firstRecordingId = responseBody(
                startRecording(
                        workspaceId,
                        REQUEST_ID,
                        TAB_ID,
                        CONTROL_TOKEN,
                        firstAccessTokenCookie,
                        csrfCredentials
                ).andExpect(status().isCreated())
        ).get("recordingId")
                .asLong();

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                secondAccessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECORDING"));
        assertThat(
                responseBody(result).get("recordingId")
                        .asLong()
        ).isNotEqualTo(firstRecordingId);
        assertThat(countRecordingSessions()).isEqualTo(2);
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 WORKSPACE_ACCESS_DENIED 403을 반환한다")
    void start_failure_workspaceAccessDenied() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("없는 워크스페이스 녹음 시작은 WORKSPACE_NOT_FOUND 404를 반환한다")
    void start_failure_workspaceNotFound() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = startRecording(
                Long.MAX_VALUE,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessTokenCookie,
                csrfCredentials
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("인증 없이 유효한 CSRF 토큰만 보내면 401을 반환한다")
    void start_failure_unauthenticated() throws Exception {
        // given
        CsrfCredentials csrfCredentials = csrfCredentials();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        1L
                ).cookie(csrfCredentials.cookie())
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                requestBody(
                                        REQUEST_ID,
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("인증은 있지만 CSRF 토큰이 없으면 403을 반환한다")
    void start_failure_missingCsrfToken() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        1L
                ).cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                requestBody(
                                        REQUEST_ID,
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("녹음 시작 요청 본문이 유효하지 않으면 400 응답에서 비밀값을 숨긴다")
    void start_failure_invalidRequestBody() throws Exception {
        // given
        long memberId = saveMember("octocat");
        Cookie accessTokenCookie = accessTokenCookie(memberId);
        CsrfCredentials csrfCredentials = csrfCredentials();
        String invalidControlToken = "invalid-control-token";

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        1L
                ).cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                requestBody(
                                        REQUEST_ID,
                                        TAB_ID,
                                        invalidControlToken
                                )
                        )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        ).doesNotContain(invalidControlToken);
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("OpenAPI JSON에 녹음 시작 계약을 공개한다")
    void openApi_success_recordingStartContract() throws Exception {
        // given
        String openApiPath = "/v3/api-docs";
        String startPath = "$.paths['/api/v1/workspaces/{workspaceId}/recordings'].post";
        String requestSchemaPath = "$.components.schemas.RecordingStartRequest";
        String requestPropertiesPath = requestSchemaPath + ".properties";
        String responseRef = "#/components/schemas/RecordingStartResponse";
        String errorResponseRef = "#/components/schemas/ErrorResponse";

        // when
        ResultActions result = mockMvc.perform(get(openApiPath));

        // then
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath(startPath).exists())
                .andExpect(
                        jsonPath(startPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value(responseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['201'].content['application/json'].schema['$ref']")
                                .value(responseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['400'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['401'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['403'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['404'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(
                        jsonPath(startPath + ".responses['409'].content['application/json'].schema['$ref']")
                                .value(errorResponseRef)
                )
                .andExpect(jsonPath(startPath + ".security[*].accessTokenCookie").exists())
                .andExpect(
                        jsonPath(startPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true))
                )
                .andExpect(
                        jsonPath(requestSchemaPath + ".required").value(
                                hasItems(
                                        "requestId",
                                        "tabId",
                                        "controlToken"
                                )
                        )
                )
                .andExpect(jsonPath(requestPropertiesPath + ".requestId.format").value("uuid"))
                .andExpect(jsonPath(requestPropertiesPath + ".tabId.format").value("uuid"))
                .andExpect(jsonPath(requestPropertiesPath + ".controlToken.writeOnly").value(true))
                .andExpect(
                        jsonPath(requestPropertiesPath + ".controlToken.pattern")
                                .value("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
                )
                .andExpect(jsonPath("$.components.schemas.RecordingStartResponse.properties.recordingId").exists())
                .andExpect(jsonPath("$.components.schemas.RecordingStartResponse.properties.status").exists())
                .andExpect(jsonPath("$.components.schemas.RecordingStartResponse.properties.startedAt").exists())
                .andExpect(jsonPath("$.components.schemas.RecordingStartResponse.properties.requestId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.RecordingStartResponse.properties.tabId").doesNotExist())
                .andExpect(
                        jsonPath("$.components.schemas.RecordingStartResponse.properties.controlToken").doesNotExist()
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingStartResponse.properties.controlTokenHash")
                                .doesNotExist()
                );
    }

    private ResultActions startRecording(
            long workspaceId,
            UUID requestId,
            UUID tabId,
            String controlToken,
            Cookie accessTokenCookie,
            CsrfCredentials csrfCredentials
    ) throws Exception {
        return mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        workspaceId
                ).cookie(
                        accessTokenCookie,
                        csrfCredentials.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrfCredentials.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                requestBody(
                                        requestId,
                                        tabId,
                                        controlToken
                                )
                        )
        );
    }

    private String requestBody(
            UUID requestId,
            UUID tabId,
            String controlToken
    ) {
        return """
                {"requestId":"%s","tabId":"%s","controlToken":"%s"}
                """.formatted(
                requestId,
                tabId,
                controlToken
        );
    }

    private JsonNode responseBody(ResultActions result) throws Exception {
        return objectMapper.readTree(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        );
    }

    @Test
    @DisplayName("같은 요청 키의 워크스페이스를 바꾸면 원래 세션만 남기고 409를 반환한다")
    void start_failure_sameRequestDifferentWorkspace() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long firstWorkspace = saveWorkspace("첫 팀");
        long secondWorkspace = saveWorkspace("다음 팀");
        saveWorkspaceMember(
                firstWorkspace,
                memberId
        );
        saveWorkspaceMember(
                secondWorkspace,
                memberId
        );
        Cookie cookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();
        startRecording(
                firstWorkspace,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                cookie,
                csrf
        ).andExpect(status().isCreated());

        // when
        ResultActions result = startRecording(
                secondWorkspace,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                cookie,
                csrf
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_START_REQUEST_CONFLICT"));
        assertThat(countRecordingSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("조각 저장 기능의 기본값은 비활성이며 PCM 시작은 기존 세션과 스트림을 바꾸지 않는다")
    void start_failure_pcmDisabledByDefault() throws Exception {
        // given
        long memberId = saveMember("octocat");
        long workspaceId = saveWorkspace("Knot 팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        Cookie accessCookie = accessTokenCookie(memberId);
        CsrfCredentials csrf = csrfCredentials();
        startRecording(
                workspaceId,
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN,
                accessCookie,
                csrf
        ).andExpect(status().isCreated());
        String before = jdbcClient.sql("SELECT row_to_json(recording_sessions)::text FROM recording_sessions")
                .query(String.class)
                .single();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        workspaceId
                ).cookie(
                        accessCookie,
                        csrf.cookie()
                )
                        .header(
                                "X-XSRF-TOKEN",
                                csrf.token()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                objectMapper.writeValueAsString(
                                        java.util.Map.of(
                                                "requestId",
                                                UUID.randomUUID(),
                                                "tabId",
                                                UUID.randomUUID(),
                                                "controlToken",
                                                CONTROL_TOKEN,
                                                "audioMode",
                                                "PCM16_CHUNKS_V1"
                                        )
                                )
                        )
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_MODE_DISABLED"));
        assertThat(
                jdbcClient.sql("SELECT row_to_json(recording_sessions)::text FROM recording_sessions")
                        .query(String.class)
                        .single()
        ).isEqualTo(before);
        assertThat(
                jdbcClient.sql("SELECT COUNT(*) FROM recording_audio_streams")
                        .query(Integer.class)
                        .single()
        ).isZero();
    }

    private void endRecording(long recordingId) {
        transactionTemplate.executeWithoutResult(status -> {
            RecordingSession session = recordingSessionRepository.findById(recordingId)
                    .orElseThrow();
            session.end(
                    session.getStartedAt()
                            .plusSeconds(60)
            );
            recordingSessionRepository.save(session);
        });
    }

    private CsrfCredentials csrfCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(CSRF_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        JsonNode responseBody = objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
        return new CsrfCredentials(
                cookie,
                responseBody.get("token")
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

    private int countRecordingSessions() {
        return jdbcClient.sql("SELECT COUNT(*) FROM recording_sessions")
                .query(Integer.class)
                .single();
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
