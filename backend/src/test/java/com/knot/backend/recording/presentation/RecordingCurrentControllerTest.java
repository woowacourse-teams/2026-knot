package com.knot.backend.recording.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.result.RecordingCurrentResult;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordingCurrentControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final Instant STARTED_AT = Instant.parse("2026-10-05T00:00:00Z");

    private RecordingService recordingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingService = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingCurrentController(recordingService))
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
    @DisplayName("활성 녹음이 있으면 200과 녹음 ID, 상태, 시작 시각, 누적 시간을 반환한다")
    void findCurrent_success() throws Exception {
        // given
        when(
                recordingService.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(
                Optional.of(
                        new RecordingCurrentResult(
                                RECORDING_ID,
                                RecordingStatus.PAUSED,
                                STARTED_AT,
                                125_000L
                        )
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/recordings/current",
                        WORKSPACE_ID
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-05T00:00:00Z"))
                .andExpect(jsonPath("$.elapsedMillis").value(125_000L));
    }

    @Test
    @DisplayName("활성 녹음이 없으면 본문 없는 204를 반환한다")
    void findCurrent_success_noContent() throws Exception {
        // given
        when(
                recordingService.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(Optional.empty());

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/recordings/current",
                        WORKSPACE_ID
                )
        );

        // then
        result.andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("Workspace 소속이 아니면 WORKSPACE_ACCESS_DENIED 403 응답을 반환한다")
    void findCurrent_failure_accessDenied() throws Exception {
        // given
        when(
                recordingService.findCurrent(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenThrow(new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));

        // when
        ResultActions result = mockMvc.perform(
                get(
                        "/api/v1/workspaces/{workspaceId}/recordings/current",
                        WORKSPACE_ID
                )
        );

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
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
