package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;

class RecordingStartControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final Instant STARTED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private RecordingService recordingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingService = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingStartController(recordingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("녹음 시작 요청이 처음 생성되면 201과 세션 식별자를 반환한다")
    void start_success_created() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.start(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(command())
                )
        ).thenReturn(createdResult());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody())
        );

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.tabId").doesNotExist())
                .andExpect(jsonPath("$.controlToken").doesNotExist());
        verify(recordingService).start(
                WORKSPACE_ID,
                MEMBER_ID,
                command()
        );
        verifyNoMoreInteractions(recordingService);
    }

    @Test
    @DisplayName("같은 시작 요청을 재시도하면 200과 기존 세션 현재 상태를 반환한다")
    void start_success_replayed() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.start(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(command())
                )
        ).thenReturn(replayedPausedResult());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody())
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.controlToken").doesNotExist());
        verify(recordingService).start(
                WORKSPACE_ID,
                MEMBER_ID,
                command()
        );
    }

    @Test
    @DisplayName("requestId가 없으면 VALIDATION_ERROR 400 응답을 반환한다")
    void start_failure_missingRequestId() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                        {"tabId":"%s","controlToken":"%s"}
                                        """.formatted(
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        assertThat(
                result.andReturn()
                        .getResolvedException()
        ).isInstanceOf(MethodArgumentNotValidException.class);
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("requestId"));
        verifyNoInteractions(recordingService);
    }

    @Test
    @DisplayName("controlToken 형식이 올바르지 않으면 VALIDATION_ERROR 400 응답에서 원문을 숨긴다")
    void start_failure_invalidControlToken() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        String invalidControlToken = "invalid-control-token";

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                        {"requestId":"%s","tabId":"%s","controlToken":"%s"}
                                        """.formatted(
                                        REQUEST_ID,
                                        TAB_ID,
                                        invalidControlToken
                                )
                        )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("controlToken"));
        assertThat(
                result.andReturn()
                        .getResponse()
                        .getContentAsString()
        ).doesNotContain(invalidControlToken);
        verifyNoInteractions(recordingService);
    }

    @Test
    @DisplayName("UUID 형식이 올바르지 않으면 INVALID_REQUEST_BODY 400 응답을 반환한다")
    void start_failure_invalidUuid() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                        {"requestId":"not-uuid","tabId":"%s","controlToken":"%s"}
                                        """.formatted(
                                        TAB_ID,
                                        CONTROL_TOKEN
                                )
                        )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(recordingService);
    }

    @Test
    @DisplayName("Command 문자열 표현은 controlToken 원문을 숨긴다")
    void commandToString_success_redactsControlToken() {
        // given
        RecordingStartCommand command = command();

        // when
        String actual = command.toString();

        // then
        assertThat(actual).contains("REDACTED")
                .doesNotContain(CONTROL_TOKEN);
    }

    private String requestBody() {
        return """
                {"requestId":"%s","tabId":"%s","controlToken":"%s"}
                """.formatted(
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN
        );
    }

    private RecordingStartCommand command() {
        return new RecordingStartCommand(
                REQUEST_ID,
                TAB_ID,
                CONTROL_TOKEN
        );
    }

    private RecordingStartResult createdResult() {
        return new RecordingStartResult(
                RECORDING_ID,
                RecordingStatus.RECORDING,
                STARTED_AT,
                true
        );
    }

    private RecordingStartResult replayedPausedResult() {
        return new RecordingStartResult(
                RECORDING_ID,
                RecordingStatus.PAUSED,
                STARTED_AT,
                false
        );
    }

    private UsernamePasswordAuthenticationToken memberAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                AuthenticatedMember.of(
                        MEMBER_ID,
                        "octocat",
                        null
                ),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
