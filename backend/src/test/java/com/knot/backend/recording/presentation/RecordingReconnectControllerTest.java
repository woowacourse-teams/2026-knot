package com.knot.backend.recording.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.command.RecordingReconnectCommand;
import com.knot.backend.recording.application.dto.result.RecordingHeartbeatResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingConnectionStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordingReconnectControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final Instant NOW = Instant.parse("2026-10-28T00:21:00Z");
    private static final UUID PREVIOUS_TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TAB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PREVIOUS_CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String CONTROL_TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFA";
    private static final String BODY = """
            {"previousTabId":"%s","previousControlToken":"%s","tabId":"%s","controlToken":"%s"}
            """.formatted(
            PREVIOUS_TAB_ID,
            PREVIOUS_CONTROL_TOKEN,
            TAB_ID,
            CONTROL_TOKEN
    );

    private RecordingService recordingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingService = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingReconnectController(recordingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("이어 받으면 200과 신호 응답과 같은 필드를 반환하고 증명 원문은 응답하지 않는다")
    void reconnect_success() throws Exception {
        // given
        when(
                recordingService.reconnect(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(
                new RecordingHeartbeatResult(
                        RECORDING_ID,
                        RecordingStatus.RECORDING,
                        1_260_000L,
                        NOW,
                        NOW.plusSeconds(1_800),
                        null,
                        null,
                        RecordingConnectionStatus.CONNECTED,
                        NOW
                )
        );

        // when
        ResultActions result = performReconnect(BODY);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.elapsedMillis").value(1_260_000L))
                .andExpect(jsonPath("$.lastSeenAt").value("2026-10-28T00:21:00Z"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-28T00:51:00Z"))
                .andExpect(jsonPath("$.endedAt").isEmpty())
                .andExpect(jsonPath("$.endReason").isEmpty())
                .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.serverNow").value("2026-10-28T00:21:00Z"))
                .andExpect(jsonPath("$.length()").value(9));
        ArgumentCaptor<RecordingReconnectCommand> command = ArgumentCaptor.forClass(RecordingReconnectCommand.class);
        verify(recordingService).reconnect(
                eq(WORKSPACE_ID),
                eq(MEMBER_ID),
                eq(RECORDING_ID),
                command.capture()
        );
        assertThat(
                command.getValue()
                        .previousTabId()
        ).isEqualTo(PREVIOUS_TAB_ID);
        assertThat(
                command.getValue()
                        .tabId()
        ).isEqualTo(TAB_ID);
        assertThat(
                command.getValue()
                        .toString()
        ).doesNotContain(
                PREVIOUS_CONTROL_TOKEN,
                CONTROL_TOKEN
        );
    }

    @Test
    @DisplayName("이미 연결 만료로 끝난 녹음이면 200 ENDED와 끝난 시각, 사유를 반환한다")
    void reconnect_success_ended() throws Exception {
        // given
        Instant endedAt = NOW.minusSeconds(2_460);
        when(
                recordingService.reconnect(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(
                new RecordingHeartbeatResult(
                        RECORDING_ID,
                        RecordingStatus.ENDED,
                        1_200_000L,
                        endedAt,
                        null,
                        endedAt,
                        RecordingEndReason.CONNECTION_EXPIRED,
                        null,
                        NOW
                )
        );

        // when
        ResultActions result = performReconnect(BODY);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").value("2026-10-27T23:40:00Z"))
                .andExpect(jsonPath("$.endReason").value("CONNECTION_EXPIRED"))
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.connectionStatus").isEmpty());
    }

    @Test
    @DisplayName("다른 탭이 먼저 이어 받았으면 RECORDING_CONTROL_DENIED 403을 반환한다")
    void reconnect_failure_controlDenied() throws Exception {
        // given
        when(
                recordingService.reconnect(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED));

        // when
        ResultActions result = performReconnect(BODY);

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"));
    }

    @Test
    @DisplayName("이전 제어 증명이 없으면 VALIDATION_ERROR 400을 반환하고 서비스를 호출하지 않는다")
    void reconnect_failure_missingPreviousControlToken() throws Exception {
        // given
        String body = """
                {"previousTabId":"%s","tabId":"%s","controlToken":"%s"}
                """.formatted(
                PREVIOUS_TAB_ID,
                TAB_ID,
                CONTROL_TOKEN
        );

        // when
        ResultActions result = performReconnect(body);

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("previousControlToken"));
        verifyNoInteractions(recordingService);
    }

    private ResultActions performReconnect(String body) throws Exception {
        return mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/reconnect",
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
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
