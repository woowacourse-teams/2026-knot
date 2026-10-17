package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingTranscriptionService;
import com.knot.backend.recording.application.RecordingTranscriptionStateService;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTranscriptionRetryAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcription/retry";
    private static final UUID PREVIOUS_ATTEMPT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final MockMvc mockMvc;
    private final AuthTokenProvider tokens;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RecordingTranscriptionStateService recordingTranscriptionStateService;
    @MockitoBean
    private RecordingTranscriptionService service;

    RecordingTranscriptionRetryAcceptanceTest(
            MockMvc mockMvc,
            AuthTokenProvider tokens,
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            RecordingTranscriptionStateService recordingTranscriptionStateService
    ) {
        this.mockMvc = mockMvc;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.recordingTranscriptionStateService = recordingTranscriptionStateService;
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE recording_sessions, workspaces, members RESTART IDENTITY CASCADE")
                .update();
        // HTTP dispatch is tested by the worker tests. Exercise real
        // authorization/state transactions here.
        when(
                service.retry(
                        anyLong(),
                        anyLong(),
                        anyLong()
                )
        ).thenAnswer(
                call -> recordingTranscriptionStateService.retry(
                        call.getArgument(0),
                        call.getArgument(1),
                        call.getArgument(2)
                )
        );
    }

    @Test
    @DisplayName("실패한 본인 녹음을 재시도하면 202와 새 시도 ID를 반환한다")
    void retry_success_newAttempt() throws Exception {
        // given
        fixture("FAILED");

        // when
        ResultActions result = retry(
                1L,
                1L
        );

        // then
        result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recordingId").value(1L))
                .andExpect(jsonPath("$.transcriptionStatus").value("QUEUED"));
        UUID nextAttempt = UUID.fromString(
                objectMapper.readTree(
                        result.andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                        .get("attemptId")
                        .asText()
        );
        assertThat(nextAttempt).isNotEqualTo(PREVIOUS_ATTEMPT);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(1L)
                        .attemptId()
        ).isEqualTo(nextAttempt);
        assertThat(
                recordingTranscriptionStateService.findSnapshot(1L)
                        .failureCode()
        ).isNull();
    }

    @Test
    @DisplayName("녹음 상세에서 전사 실패 상태와 시도 ID를 확인한다")
    void detail_success_failedTranscription() throws Exception {
        // given
        fixture("FAILED");

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/workspaces/1/recordings/1").cookie(token(1L)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptionAttemptId").value(PREVIOUS_ATTEMPT.toString()))
                .andExpect(jsonPath("$.transcriptionStatus").value("FAILED"))
                .andExpect(jsonPath("$.transcriptionFailureCode").value("INFERENCE_FAILED"));
    }

    @Test
    @DisplayName("진행 중인 전사는 재시도하지 않는다")
    void retry_failure_processing() throws Exception {
        // given
        fixture("PROCESSING");

        // when
        ResultActions result = retry(
                1L,
                1L
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSCRIPTION_RETRY_NOT_ALLOWED"));
        assertThat(
                recordingTranscriptionStateService.findSnapshot(1L)
                        .attemptId()
        ).isEqualTo(PREVIOUS_ATTEMPT);
    }

    @Test
    @DisplayName("같은 워크스페이스의 다른 멤버는 전사를 재시도하지 못한다")
    void retry_failure_otherMember() throws Exception {
        // given
        fixture("FAILED");
        jdbc.sql("INSERT INTO members (id, nickname) OVERRIDING SYSTEM VALUE VALUES (2, 'other')")
                .update();
        jdbc.sql(
                "INSERT INTO workspace_members (workspace_id, member_id, role, joined_at) "
                        + "VALUES (1, 2, 'MEMBER', now())"
        )
                .update();

        // when
        ResultActions result = retry(
                1L,
                2L
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
        assertThat(
                recordingTranscriptionStateService.findSnapshot(1L)
                        .attemptId()
        ).isEqualTo(PREVIOUS_ATTEMPT);
    }

    @Test
    @DisplayName("다른 워크스페이스 경로로 전사를 재시도하면 녹음을 찾을 수 없다")
    void retry_failure_otherWorkspace() throws Exception {
        // given
        fixture("FAILED");
        jdbc.sql("INSERT INTO workspaces (id, name, created_at) VALUES (2, 'other', now())")
                .update();
        jdbc.sql(
                "INSERT INTO workspace_members (workspace_id, member_id, role, joined_at) VALUES (2, 1, 'OWNER', now())"
        )
                .update();

        // when
        ResultActions result = retry(
                2L,
                1L
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("원본 오디오가 삭제된 전사는 재시도하지 않는다")
    void retry_failure_deletedAudio() throws Exception {
        // given
        fixture("FAILED");
        jdbc.sql("UPDATE recording_audio_uploads SET deleted_at = now() WHERE recording_id = 1")
                .update();

        // when
        ResultActions result = retry(
                1L,
                1L
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_UPLOAD_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("전사 시도가 없는 녹음은 재시도하지 않는다")
    void retry_failure_missingAttempt() throws Exception {
        // given
        fixture("FAILED");
        jdbc.sql("DELETE FROM recording_transcriptions WHERE recording_session_id = 1")
                .update();

        // when
        ResultActions result = retry(
                1L,
                1L
        );

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSCRIPTION_RETRY_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 전사 재시도를 거절한다")
    void retry_failure_missingCsrf() throws Exception {
        // given
        fixture("FAILED");

        // when
        ResultActions result = mockMvc.perform(
                post(
                        PATH,
                        1L,
                        1L
                ).cookie(token(1L))
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(
                recordingTranscriptionStateService.findSnapshot(1L)
                        .attemptId()
        ).isEqualTo(PREVIOUS_ATTEMPT);
    }

    @Test
    @DisplayName("OpenAPI에 전사 재시도 202 응답과 CSRF 계약을 공개한다")
    void openApi_success_retryContract() throws Exception {
        // given
        String path = "$.paths['" + PATH + "'].post";

        // when
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(status().isOk())
                .andExpect(
                        jsonPath(path + ".responses['202'].content['application/json'].schema['$ref']")
                                .value("#/components/schemas/RecordingTranscriptionRetryResponse")
                )
                .andExpect(jsonPath(path + ".parameters[?(@.name == 'X-XSRF-TOKEN')].required").value(hasItems(true)));
    }

    private ResultActions retry(
            long workspaceId,
            long memberId
    ) throws Exception {
        MvcResult csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return mockMvc.perform(
                post(
                        PATH,
                        workspaceId,
                        1L
                ).cookie(
                        token(memberId),
                        csrf.getResponse()
                                .getCookie("XSRF-TOKEN")
                )
                        .header(
                                "X-XSRF-TOKEN",
                                objectMapper.readTree(
                                        csrf.getResponse()
                                                .getContentAsString()
                                )
                                        .get("token")
                                        .asText()
                        )
        );
    }

    private Cookie token(long memberId) {
        return new Cookie(
                "KNOT_ACCESS_TOKEN",
                tokens.issue(
                        AuthenticatedMember.of(
                                memberId,
                                "member",
                                null
                        )
                )
        );
    }

    private void fixture(String transcriptionStatus) {
        jdbc.sql("INSERT INTO members (id, nickname) OVERRIDING SYSTEM VALUE VALUES (1, 'member')")
                .update();
        jdbc.sql("INSERT INTO workspaces (id, name, created_at) VALUES (1, 'team', now() - interval '1 hour')")
                .update();
        jdbc.sql(
                "INSERT INTO workspace_members (workspace_id, member_id, role, joined_at) VALUES (1, 1, 'OWNER', now())"
        )
                .update();
        jdbc.sql("""
                INSERT INTO recording_sessions
                    (id, workspace_id, member_id, request_id, tab_id, control_token_hash, status, started_at,
                    ended_at, end_reason, last_seen_at, accumulated_recording_millis)
                OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, :request, :tab, :hash, 'ENDED', now() - interval '1 hour',
                    now() - interval '59 minutes', 'USER_ENDED', now() - interval '59 minutes', 60000)
                """)
                .param(
                        "request",
                        UUID.randomUUID()
                )
                .param(
                        "tab",
                        UUID.randomUUID()
                )
                .param(
                        "hash",
                        "a".repeat(64)
                )
                .update();
        jdbc.sql("""
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                VALUES (1, 'recordings/1/audio.webm', 'audio/webm', 1024, 'COMPLETED',
                    now() - interval '58 minutes', now() - interval '57 minutes')
                """)
                .update();
        jdbc.sql("""
                INSERT INTO recording_transcriptions
                    (recording_session_id, attempt_id, status, failure_code, started_at)
                VALUES (1, :attempt, :status, :failure, now() - interval '56 minutes')
                """)
                .param(
                        "attempt",
                        PREVIOUS_ATTEMPT
                )
                .param(
                        "status",
                        transcriptionStatus
                )
                .param(
                        "failure",
                        transcriptionStatus.equals("FAILED") ? "INFERENCE_FAILED" : null
                )
                .update();
    }
}
