package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioChunkStorage;
import com.knot.backend.recording.application.RecordingLog;
import com.knot.backend.recording.application.dto.result.PresignedAudioChunk;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.testsupport.LogCapture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class RecordingReconnectAcceptanceTest {
    private static final String JWT_COOKIE_NAME = "KNOT_ACCESS_TOKEN";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String PREVIOUS_CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String CONTROL_TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFA";
    private static final String DUPLICATED_CONTROL_TOKEN = "ZYXWVUTSRQPONMLKJIHGFEDCBA9876543210zyxwvuA";
    private static final UUID PREVIOUS_TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TAB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DUPLICATED_TAB_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String OTHER_CHECKSUM = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-10-05T00:01:00Z");
    private static final String RECORDING_PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}";
    private static final String RECONNECT_PATH = RECORDING_PATH + "/reconnect";
    private static final String HEARTBEAT_PATH = RECORDING_PATH + "/heartbeat";
    private static final String CHUNKS_PATH = RECORDING_PATH + "/audio-chunks";
    private static final String COMPLETE_PATH = CHUNKS_PATH + "/{sequence}/complete";

    private final MockMvc mockMvc;
    private final AuthTokenProvider authTokenProvider;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    @MockitoBean
    private RecordingAudioChunkStorage storage;

    RecordingReconnectAcceptanceTest(
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
    @DisplayName("새로고침한 녹음 탭이 이어 받으면 신호 응답과 같은 필드를 받고 새 증명으로만 제어한다")
    void reconnect_success_handsOver() throws Exception {
        // given
        Fixture fixture = startedRecording();
        try (LogCapture logs = new LogCapture(RecordingLog.class)) {
            // when
            ResultActions result = reconnect(
                    fixture,
                    reconnectBody(
                            TAB_ID,
                            CONTROL_TOKEN
                    )
            );

            // then
            String body = result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.recordingId").value(fixture.recordingId()))
                    .andExpect(jsonPath("$.status").value("RECORDING"))
                    .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                    .andExpect(jsonPath("$.endedAt").isEmpty())
                    .andExpect(jsonPath("$.endReason").isEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).doesNotContain(
                    PREVIOUS_CONTROL_TOKEN,
                    CONTROL_TOKEN,
                    TAB_ID.toString()
            );
            assertThat(logs.events()).noneMatch(
                    event -> event.getFormattedMessage()
                            .contains(CONTROL_TOKEN)
                            || event.getFormattedMessage()
                                    .contains(PREVIOUS_CONTROL_TOKEN)
            );
        }
        heartbeat(
                fixture,
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        heartbeat(
                fixture,
                TAB_ID,
                CONTROL_TOKEN
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDING"));
    }

    @Test
    @DisplayName("같은 새 증명으로 다시 보낸 이어 받기는 같은 녹음 상태를 돌려준다")
    void reconnect_success_retry() throws Exception {
        // given
        Fixture fixture = startedRecording();
        reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        ).andExpect(status().isOk());

        // when
        ResultActions result = reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"));
    }

    @Test
    @DisplayName("복제된 두 탭이 동시에 이어 받으면 먼저 접수된 한 탭만 녹음 탭이 되고 다른 탭은 403이다")
    void reconnect_success_onlyOneDuplicatedTab() throws Exception {
        // given
        Fixture fixture = startedRecording();
        String first = reconnectBody(
                TAB_ID,
                CONTROL_TOKEN
        );
        String second = reconnectBody(
                DUPLICATED_TAB_ID,
                DUPLICATED_CONTROL_TOKEN
        );

        // when
        List<Integer> statuses = reconnectTogether(
                fixture,
                first,
                second
        );

        // then
        assertThat(statuses).containsExactlyInAnyOrder(
                200,
                403
        );
        UUID currentTabId = jdbcClient.sql("SELECT tab_id FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        fixture.recordingId()
                )
                .query(UUID.class)
                .single();
        assertThat(currentTabId).isIn(
                TAB_ID,
                DUPLICATED_TAB_ID
        );
    }

    @Test
    @DisplayName("끊김 유예가 지난 녹음을 이어 받으면 200 ENDED와 마지막 생존 시각으로 끝난 결과를 받고 증명은 바뀌지 않는다")
    void reconnect_success_expired() throws Exception {
        // given
        Fixture fixture = startedRecording();
        shiftBack(
                fixture,
                35
        );
        Instant lastSeenAt = lastSeenAt(fixture);

        // when
        ResultActions result = reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("CONNECTION_EXPIRED"))
                .andExpect(jsonPath("$.endedAt").value(lastSeenAt.toString()))
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.connectionStatus").isEmpty());
        assertThat(
                jdbcClient.sql("SELECT tab_id FROM recording_sessions WHERE id = :id")
                        .param(
                                "id",
                                fixture.recordingId()
                        )
                        .query(UUID.class)
                        .single()
        ).isEqualTo(PREVIOUS_TAB_ID);
    }

    @Test
    @DisplayName("폐기된 녹음의 이어 받기는 409 RECORDING_ALREADY_DISCARDED다")
    void reconnect_failure_discarded() throws Exception {
        // given
        Fixture fixture = startedRecording();
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET status = 'DISCARDED', current_interval_started_at = NULL,
                    ended_at = last_seen_at
                WHERE id = :id
                """)
                .param(
                        "id",
                        fixture.recordingId()
                )
                .update();

        // when
        ResultActions result = reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ALREADY_DISCARDED"));
    }

    @Test
    @DisplayName("새 증명이 이전 증명과 같으면 400 INVALID_RECORDING_DATA다")
    void reconnect_failure_sameProof() throws Exception {
        // given
        Fixture fixture = startedRecording();

        // when
        ResultActions result = reconnect(
                fixture,
                reconnectBody(
                        PREVIOUS_TAB_ID,
                        PREVIOUS_CONTROL_TOKEN
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECORDING_DATA"));
    }

    @Test
    @DisplayName("이어 받은 뒤 이전 증명의 같은 요청 키 시작 재요청은 409 RECORDING_START_REQUEST_CONFLICT다")
    void reconnect_success_previousStartRetryConflicts() throws Exception {
        // given
        Fixture fixture = startedRecording();
        reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        ).andExpect(status().isOk());

        // when
        ResultActions result = start(
                fixture.workspaceId(),
                fixture.memberId(),
                fixture.requestId()
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_START_REQUEST_CONFLICT"));
    }

    @Test
    @DisplayName("이어 받으면 확인되지 않은 예약을 지우고 audio-save의 확인된 위치 다음부터 새 증명으로 이어 예약한다")
    void reconnect_success_continuesAfterVerifiedChunk() throws Exception {
        // given
        Fixture fixture = startedRecording();
        reserve(
                fixture,
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN,
                chunkBody(
                        1,
                        0,
                        CHECKSUM
                )
        ).andExpect(status().isCreated());
        complete(
                fixture,
                1,
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN
        ).andExpect(status().isOk());
        reserve(
                fixture,
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN,
                chunkBody(
                        2,
                        80000,
                        CHECKSUM
                )
        ).andExpect(status().isCreated());

        // when
        reconnect(
                fixture,
                reconnectBody(
                        TAB_ID,
                        CONTROL_TOKEN
                )
        ).andExpect(status().isOk());
        ResultActions state = readState(fixture);
        ResultActions next = reserve(
                fixture,
                TAB_ID,
                CONTROL_TOKEN,
                chunkBody(
                        2,
                        80000,
                        OTHER_CHECKSUM
                )
        );

        // then
        state.andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedThroughSequence").value(1))
                .andExpect(jsonPath("$.verifiedThroughFrame").value(80000))
                .andExpect(jsonPath("$.missingRanges").isEmpty());
        next.andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(2));
        complete(
                fixture,
                2,
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN
        ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("OpenAPI JSON에 이어 받기 계약을 공개한다")
    void openApi_success_reconnectContract() throws Exception {
        // given
        String reconnectPath = "$.paths['" + RECONNECT_PATH + "'].post";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath(reconnectPath + ".summary").value("같은 탭 새로고침 뒤 녹음 이어 받기"))
                .andExpect(
                        jsonPath(reconnectPath + ".responses['200'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingHeartbeatResponse")
                )
                .andExpect(
                        jsonPath(reconnectPath + ".requestBody.content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingReconnectRequest")
                )
                .andExpect(jsonPath(reconnectPath + ".responses['409']").exists())
                .andExpect(
                        jsonPath("$.components.schemas.RecordingReconnectRequest.required").value(
                                hasItems(
                                        "previousTabId",
                                        "previousControlToken",
                                        "tabId",
                                        "controlToken"
                                )
                        )
                )
                .andExpect(
                        jsonPath(reconnectPath + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required")
                                .value(hasItems(true))
                );
    }

    private List<Integer> reconnectTogether(
            Fixture fixture,
            String first,
            String second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<Integer>> tasks = List.of(
                () -> reconnectAfterBarrier(
                        barrier,
                        fixture,
                        first
                ),
                () -> reconnectAfterBarrier(
                        barrier,
                        fixture,
                        second
                )
        );
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        }
    }

    private int reconnectAfterBarrier(
            CyclicBarrier barrier,
            Fixture fixture,
            String body
    ) throws Exception {
        MockHttpServletRequestBuilder request = writeRequest(
                RECONNECT_PATH,
                fixture
        );
        barrier.await();
        return mockMvc.perform(request.content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private ResultActions reconnect(
            Fixture fixture,
            String body
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        RECONNECT_PATH,
                        fixture
                ).content(body)
        );
    }

    private ResultActions heartbeat(
            Fixture fixture,
            UUID tabId,
            String controlToken
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        HEARTBEAT_PATH,
                        fixture
                ).content(
                        controlBody(
                                tabId,
                                controlToken
                        )
                )
        );
    }

    private ResultActions reserve(
            Fixture fixture,
            UUID tabId,
            String controlToken,
            String chunk
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        CHUNKS_PATH,
                        fixture
                ).content(
                        """
                                {"tabId":"%s","controlToken":"%s",%s}
                                """.formatted(
                                tabId,
                                controlToken,
                                chunk
                        )
                )
        );
    }

    private ResultActions complete(
            Fixture fixture,
            int sequence,
            UUID tabId,
            String controlToken
    ) throws Exception {
        return mockMvc.perform(
                writeRequest(
                        COMPLETE_PATH,
                        fixture,
                        sequence
                ).content(
                        controlBody(
                                tabId,
                                controlToken
                        )
                )
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

    private ResultActions start(
            long workspaceId,
            long memberId,
            UUID requestId
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        return mockMvc.perform(
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
                                        requestId,
                                        PREVIOUS_TAB_ID,
                                        PREVIOUS_CONTROL_TOKEN
                                )
                        )
        );
    }

    private MockHttpServletRequestBuilder writeRequest(
            String path,
            Fixture fixture,
            Object... extra
    ) throws Exception {
        CsrfCredentials csrf = csrfCredentials();
        List<Object> parameters = new ArrayList<>(
                List.of(
                        fixture.workspaceId(),
                        fixture.recordingId()
                )
        );
        parameters.addAll(List.of(extra));
        return post(
                path,
                parameters.toArray()
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

    private String reconnectBody(
            UUID tabId,
            String controlToken
    ) {
        return """
                {"previousTabId":"%s","previousControlToken":"%s","tabId":"%s","controlToken":"%s"}
                """.formatted(
                PREVIOUS_TAB_ID,
                PREVIOUS_CONTROL_TOKEN,
                tabId,
                controlToken
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

    private String chunkBody(
            int sequence,
            long offset,
            String checksum
    ) {
        return """
                "sequence":%d,"sampleOffset":%d,"frameCount":80000,"byteLength":160000,"checksumSha256":"%s"
                """.formatted(
                sequence,
                offset,
                checksum
        )
                .strip();
    }

    private Fixture startedRecording() throws Exception {
        long memberId = saveMember("member");
        long workspaceId = saveWorkspace("팀");
        saveWorkspaceMember(
                workspaceId,
                memberId
        );
        UUID requestId = UUID.randomUUID();
        MvcResult result = start(
                workspaceId,
                memberId,
                requestId
        ).andExpect(status().isCreated())
                .andReturn();
        return new Fixture(
                workspaceId,
                memberId,
                readBody(result).get("recordingId")
                        .asLong(),
                requestId
        );
    }

    // 실제 30분을 기다리지 않도록 녹음의 모든 시각을 같은 만큼 과거로 옮겨 끊김 유예가 지난 상태를 만든다
    private void shiftBack(
            Fixture fixture,
            int minutes
    ) {
        jdbcClient.sql("""
                UPDATE recording_sessions
                SET started_at = started_at - make_interval(mins => :minutes),
                    current_interval_started_at = current_interval_started_at - make_interval(mins => :minutes),
                    last_seen_at = last_seen_at - make_interval(mins => :minutes)
                WHERE id = :id
                """)
                .param(
                        "minutes",
                        minutes
                )
                .param(
                        "id",
                        fixture.recordingId()
                )
                .update();
    }

    private Instant lastSeenAt(Fixture fixture) {
        return jdbcClient.sql("SELECT last_seen_at FROM recording_sessions WHERE id = :id")
                .param(
                        "id",
                        fixture.recordingId()
                )
                .query(Instant.class)
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
            long recordingId,
            UUID requestId
    ) {
    }

    private record CsrfCredentials(
            Cookie cookie,
            String token
    ) {
    }
}
