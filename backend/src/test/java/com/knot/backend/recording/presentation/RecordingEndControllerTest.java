package com.knot.backend.recording.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
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

class RecordingEndControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final Instant ENDED_AT = Instant.parse("2026-10-05T00:10:00Z");
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final String BODY = """
            {"tabId":"22222222-2222-2222-2222-222222222222","controlToken":"%s"}
            """.formatted(CONTROL_TOKEN);

    private RecordingService recordingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingService = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingEndController(recordingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("최초 탭이 녹음을 종료하면 200과 ENDED 상태, 서버 종료 시각을 반환한다")
    void end_success() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.end(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID,
                        new RecordingControlCommand(
                                TAB_ID,
                                CONTROL_TOKEN
                        )
                )
        ).thenReturn(
                new RecordingEndResult(
                        RECORDING_ID,
                        RecordingStatus.ENDED,
                        ENDED_AT
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").value("2026-10-05T00:10:00Z"));
    }

    @Test
    @DisplayName("최초 탭이 아니면 RECORDING_CONTROL_DENIED 403 응답을 반환한다")
    void end_failure_controlDenied() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.end(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED));

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECORDING_CONTROL_DENIED"))
                .andExpect(jsonPath("$.message").value("녹음을 제어할 권한이 없습니다"));
    }

    @Test
    @DisplayName("숫자가 아닌 녹음 ID는 400을 반환하고 서비스를 호출하지 않는다")
    void end_failure_nonNumericRecordingId() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/abc/end",
                        WORKSPACE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
        );

        // then
        result.andExpect(status().isBadRequest());
        verifyNoInteractions(recordingService);
    }

    @Test
    @DisplayName("제어 증명이 없으면 VALIDATION_ERROR 400을 반환하고 서비스를 호출하지 않는다")
    void end_failure_missingControlToken() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tabId":"%s"}
                                """.formatted(TAB_ID))
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("controlToken"));
        verifyNoInteractions(recordingService);
    }

    @Test
    @DisplayName("요청 본문이 없으면 INVALID_REQUEST_BODY 400을 반환하고 서비스를 호출하지 않는다")
    void end_failure_missingBody() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end",
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(recordingService);
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
