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
import com.knot.backend.recording.application.dto.result.RecordingResumeResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
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

class RecordingResumeControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final Instant RESUMED_AT = Instant.parse("2026-10-05T00:10:00Z");
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
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingResumeController(recordingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("최초 탭이 재개하면 200과 RECORDING, 재개 시각, 누적 시간을 반환한다")
    void resume_success() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.resume(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(
                new RecordingResumeResult(
                        RECORDING_ID,
                        RecordingStatus.RECORDING,
                        RESUMED_AT,
                        600_000L
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/resume",
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.resumedAt").value("2026-10-05T00:10:00Z"))
                .andExpect(jsonPath("$.elapsedMillis").value(600_000L));
    }

    @Test
    @DisplayName("최초 탭이 아니면 RECORDING_CONTROL_DENIED 403 응답을 반환한다")
    void resume_failure_controlDenied() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());
        when(
                recordingService.resume(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_CONTROL_DENIED));

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/resume",
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
    @DisplayName("제어 증명이 없으면 VALIDATION_ERROR 400을 반환하고 서비스를 호출하지 않는다")
    void resume_failure_missingControlToken() throws Exception {
        // given
        SecurityContextHolder.getContext()
                .setAuthentication(memberAuthentication());

        // when
        ResultActions result = mockMvc.perform(
                post(
                        "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/resume",
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
