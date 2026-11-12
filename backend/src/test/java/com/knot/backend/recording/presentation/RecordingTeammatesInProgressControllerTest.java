package com.knot.backend.recording.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.TeammateRecordingService;
import com.knot.backend.recording.application.dto.result.CurrentRecordingStatus;
import com.knot.backend.recording.application.dto.result.TeammateRecordingResult;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
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

class RecordingTeammatesInProgressControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/teammates-in-progress";

    private TeammateRecordingService teammateRecordingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teammateRecordingService = mock(TeammateRecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingTeammatesInProgressController(teammateRecordingService))
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
    @DisplayName("팀원의 진행 중 녹음을 items 배열에 녹음 ID, 상태, 이름, 시작 시각, 누적 시간으로 반환한다")
    void findTeammatesInProgress_success() throws Exception {
        // given
        when(
                teammateRecordingService.findInProgress(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(
                List.of(
                        new TeammateRecordingResult(
                                34L,
                                CurrentRecordingStatus.RECORDING,
                                "루덴스 님의 녹음",
                                Instant.parse("2026-11-12T01:00:00Z"),
                                65_000L
                        ),
                        new TeammateRecordingResult(
                                33L,
                                CurrentRecordingStatus.PAUSED,
                                "흑곰 님의 녹음",
                                Instant.parse("2026-11-12T00:30:00Z"),
                                125_000L
                        )
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].recordingId").value(34L))
                .andExpect(jsonPath("$.items[0].status").value("RECORDING"))
                .andExpect(jsonPath("$.items[0].title").value("루덴스 님의 녹음"))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-11-12T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].elapsedMillis").value(65_000L))
                .andExpect(jsonPath("$.items[0].length()").value(5))
                .andExpect(jsonPath("$.items[1].status").value("PAUSED"));
    }

    @Test
    @DisplayName("팀원의 진행 중 녹음이 없으면 200과 빈 items를 반환한다")
    void findTeammatesInProgress_success_empty() throws Exception {
        // given
        when(
                teammateRecordingService.findInProgress(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenReturn(List.of());

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("워크스페이스 소속이 아니면 403을 반환한다")
    void findTeammatesInProgress_fail_accessDenied() throws Exception {
        // given
        when(
                teammateRecordingService.findInProgress(
                        WORKSPACE_ID,
                        MEMBER_ID
                )
        ).thenThrow(new WorkspaceException(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED));

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
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
