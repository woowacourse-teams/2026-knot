package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import java.util.Map;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
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
class RecordingAudioChunkAcceptanceTest {
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
    private final Clock clock;
    @MockitoBean
    private RecordingAudioChunkStorage storage;

    RecordingAudioChunkAcceptanceTest(
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
    void setUp() {
        jdbcClient.sql("""
                TRUNCATE TABLE recording_audio_chunks, recording_audio_streams, recording_sessions,
                    workspace_invitations, workspace_members, workspaces, oauth_identities, members
                RESTART IDENTITY CASCADE
                """)
                .update();
        when(
                storage.presignUpload(
                        anyString(),
                        anyLong(),
                        anyString(),
                        any()
                )
        ).thenAnswer(
                invocation -> new PresignedAudioChunk(
                        "https://storage.example/upload",
                        invocation.getArgument(3),
                        Map.of(
                                "if-none-match",
                                "*"
                        )
                )
        );
        when(storage.findStoredObject(anyString())).thenReturn(
                new StoredAudioChunk(
                        true,
                        160000L,
                        "application/octet-stream",
                        CHECKSUM,
                        "version-1"
                )
        );
    }

    @Test
    @DisplayName("조각 예약과 저장 확인 후 연속 저장 범위가 조회된다")
    void complete_success_andReadState() throws Exception {
        // given
        Fixture fixture = startedRecording();
        reserve(
                fixture,
                body(
                        1,
                        0
                )
        ).andExpect(status().isCreated());
        complete(
                fixture,
                1
        ).andExpect(status().isOk());

        // when
        ResultActions result = readState(fixture);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.audioSaveStatus").value("CAPTURING"))
                .andExpect(jsonPath("$.verifiedThroughSequence").value(1))
                .andExpect(jsonPath("$.verifiedThroughFrame").value(80000))
                .andExpect(jsonPath("$.verifiedAudioMillis").value(5000))
                .andExpect(jsonPath("$.verifiedRanges[0].start").value(0))
                .andExpect(jsonPath("$.verifiedRanges[0].endExclusive").value(80000));
    }

    @Test
    @DisplayName("뒤쪽 조각만 저장되면 연속 저장 범위는 0이다")
    void complete_outOfOrder() throws Exception {
        // given
        Fixture fixture = startedRecording();
        reserve(
                fixture,
                body(
                        1,
                        0
                )
        ).andExpect(status().isCreated());
        reserve(
                fixture,
                body(
                        2,
                        80000
                )
        ).andExpect(status().isCreated());
        complete(
                fixture,
                2
        ).andExpect(status().isOk());

        // when
        ResultActions result = readState(fixture);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedThroughSequence").value(0))
                .andExpect(jsonPath("$.verifiedThroughFrame").value(0))
                .andExpect(jsonPath("$.verifiedRanges[0].start").value(80000))
                .andExpect(jsonPath("$.verifiedRanges[0].endExclusive").value(160000));
    }

    @Test
    @DisplayName("등록 번호를 건너뛰면 다음 기대 번호와 409를 반환한다")
    void reserve_sequenceGap() throws Exception {
        // given
        Fixture fixture = startedRecording();

        // when
        ResultActions result = reserve(
                fixture,
                body(
                        2,
                        80000
                )
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_SEQUENCE_GAP"))
                .andExpect(jsonPath("$.expectedNextSequence").value(1));
    }

    @Test
    @DisplayName("인증됐어도 CSRF 증명 없이 조각을 예약하면 403이다")
    void reserve_missingCsrf() throws Exception {
        // given
        Fixture fixture = startedRecording();

        // when
        ResultActions result = mockMvc.perform(
                post(
                        CHUNKS_PATH,
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(accessTokenCookie(fixture.memberId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                body(
                                        1,
                                        0
                                )
                        )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("인증 쿠키 없이 저장 상태를 조회하면 401이다")
    void findState_unauthenticated() throws Exception {
        // given
        Fixture fixture = startedRecording();

        // when
        ResultActions result = mockMvc.perform(
                get(
                        RECORDING_PATH + "/audio-save",
                        fixture.workspaceId(),
                        fixture.recordingId()
                )
        );

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버는 조각을 예약할 수 없다")
    void reserve_otherOwner() throws Exception {
        // given
        Fixture fixture = startedRecording();
        long otherId = saveMember("other");
        saveWorkspaceMember(
                fixture.workspaceId(),
                otherId
        );
        Fixture other = new Fixture(
                fixture.workspaceId(),
                otherId,
                fixture.recordingId()
        );

        // when
        ResultActions result = reserve(
                other,
                body(
                        1,
                        0
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("다른 Workspace 경로로 조각을 예약하면 거절한다")
    void reserve_otherWorkspace() throws Exception {
        // given
        Fixture fixture = startedRecording();
        long otherWorkspaceId = saveWorkspace("다른 팀");
        saveWorkspaceMember(
                otherWorkspaceId,
                fixture.memberId()
        );
        Fixture other = new Fixture(
                otherWorkspaceId,
                fixture.memberId(),
                fixture.recordingId()
        );

        // when
        ResultActions result = reserve(
                other,
                body(
                        1,
                        0
                )
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 사용자의 다른 탭은 조각을 예약할 수 없다")
    void reserve_otherTab() throws Exception {
        // given
        Fixture fixture = startedRecording();
        String requestBody = body(
                1,
                0
        ).replace(
                TAB_ID.toString(),
                UUID.randomUUID()
                        .toString()
        );

        // when
        ResultActions result = reserve(
                fixture,
                requestBody
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("저장 상태 GET은 heartbeat와 조각 행을 변경하거나 S3를 조회하지 않는다")
    void findState_readOnly() throws Exception {
        // given
        Fixture fixture = startedRecording();
        String previousSeen = lastSeen(fixture);
        clearInvocations(storage);

        // when
        ResultActions result = readState(fixture);

        // then
        result.andExpect(status().isOk());
        assertThat(lastSeen(fixture)).isEqualTo(previousSeen);
        assertThat(
                jdbcClient.sql("SELECT COUNT(*) FROM recording_audio_chunks")
                        .query(Long.class)
                        .single()
        ).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("OpenAPI는 예약과 완료와 저장 조회 계약을 공개한다")
    void openApi_schema() throws Exception {
        // given
        String reservePath = "$.paths['" + CHUNKS_PATH + "'].post";
        String completePath = "$.paths['" + COMPLETE_PATH + "'].post";
        String statePath = "$.paths['" + RECORDING_PATH + "/audio-save'].get";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(reservePath + ".responses['201'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioChunkReservationResponse")
                )
                .andExpect(
                        jsonPath(completePath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioChunkCompletionResponse")
                )
                .andExpect(
                        jsonPath(statePath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingAudioSaveResponse")
                )
                .andExpect(
                        jsonPath("$.components.schemas.RecordingAudioChunkRequest.required").value(
                                hasItems(
                                        "sequence",
                                        "sampleOffset",
                                        "frameCount",
                                        "byteLength",
                                        "checksumSha256",
                                        "tabId",
                                        "controlToken"
                                )
                        )
                );
    }

    private ResultActions reserve(
            Fixture fixture,
            String body
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        CHUNKS_PATH,
                        fixture
                ).content(body)
        );
    }

    private ResultActions complete(
            Fixture fixture,
            int sequence
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        COMPLETE_PATH,
                        fixture,
                        sequence
                ).content(controlBody())
        );
    }

    private ResultActions readState(Fixture fixture) throws Exception {
        return mockMvc.perform(
                get(
                        RECORDING_PATH + "/audio-save",
                        fixture.workspaceId(),
                        fixture.recordingId()
                ).cookie(accessTokenCookie(fixture.memberId()))
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder writeRequest(
            String path,
            Fixture fixture,
            Object... extra
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        Object[] parameters = extra.length == 0
                ? new Object[]{fixture.workspaceId(), fixture.recordingId()}
                : new Object[]{fixture.workspaceId(), fixture.recordingId(), extra[0]};
        return post(
                path,
                parameters
        ).cookie(
                accessTokenCookie(fixture.memberId()),
                csrf.cookie()
        )
                .header(
                        "X-XSRF-TOKEN",
                        csrf.token()
                )
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String body(
            int sequence,
            long offset
    ) {
        return """
                {"tabId":"%s","controlToken":"%s","sequence":%d,"sampleOffset":%d,
                 "frameCount":80000,"byteLength":160000,"checksumSha256":"%s"}
                """.formatted(
                TAB_ID,
                CONTROL_TOKEN,
                sequence,
                offset,
                CHECKSUM
        );
    }

    private String controlBody() {
        return """
                {"tabId":"%s","controlToken":"%s"}
                """.formatted(
                TAB_ID,
                CONTROL_TOKEN
        );
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

    private String lastSeen(Fixture fixture) {
        return jdbcClient.sql("SELECT last_seen_at::text FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        fixture.recordingId()
                )
                .query(String.class)
                .single();
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
