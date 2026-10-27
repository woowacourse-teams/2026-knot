package com.knot.backend.recording.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecentRecordingListService;
import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.application.dto.result.RecentRecordingListResult;
import com.knot.backend.recording.application.dto.result.RecentRecordingRowResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecentRecordingControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings";

    private RecentRecordingListService recentRecordingListService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recentRecordingListService = mock(RecentRecordingListService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecentRecordingController(recentRecordingListService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                AuthenticatedMember.of(
                                        MEMBER_ID,
                                        "루덴스",
                                        null
                                ),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))
                        )
                );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("최근 녹음 한 페이지를 녹음 ID·표시 제목·시작 시각·누적 길이·처리 상태·재시도 가능 여부와 다음 커서로 반환한다")
    void find_success() throws Exception {
        // given
        when(
                recentRecordingListService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RecentRecordingListParameters.of(
                                null,
                                null
                        )
                )
        ).thenReturn(
                new RecentRecordingListResult(
                        List.of(
                                new RecentRecordingRowResult(
                                        512L,
                                        "회원 탈퇴 정책 논의",
                                        Instant.parse("2026-10-22T01:30:00Z"),
                                        1980,
                                        RecordingDocumentProcessingStatus.DOCUMENT_FAILED,
                                        true,
                                        List.of(
                                                88L,
                                                91L
                                        )
                                )
                        ),
                        "next"
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
                .andExpect(jsonPath("$.items[0].recordingId").value(512))
                .andExpect(jsonPath("$.items[0].title").value("회원 탈퇴 정책 논의"))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-22T01:30:00Z"))
                .andExpect(jsonPath("$.items[0].recordingDurationSeconds").value(1980))
                .andExpect(jsonPath("$.items[0].processingStatus").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.items[0].retryable").value(true))
                .andExpect(jsonPath("$.items[0].retryableJobIds[0]").value(88))
                .andExpect(jsonPath("$.items[0].retryableJobIds[1]").value(91))
                .andExpect(jsonPath("$.items[0].length()").value(7))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    @DisplayName("cursor와 size를 그대로 넘긴다")
    void find_success_passesPaging() throws Exception {
        // given
        when(
                recentRecordingListService.find(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        any()
                )
        ).thenReturn(
                new RecentRecordingListResult(
                        List.of(),
                        null
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID
                ).param(
                        "cursor",
                        "abc"
                )
                        .param(
                                "size",
                                "100"
                        )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(recentRecordingListService).find(
                WORKSPACE_ID,
                MEMBER_ID,
                RecentRecordingListParameters.of(
                        "abc",
                        100
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "abc"})
    @DisplayName("범위 밖이거나 숫자가 아닌 size는 400 INVALID_PARAMETER다")
    void find_fail_invalidSize(String size) throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID
                ).param(
                        "size",
                        size
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(recentRecordingListService);
    }

    @Test
    @DisplayName("빈 cursor는 400 INVALID_PARAMETER다")
    void find_fail_blankCursor() throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID
                ).param(
                        "cursor",
                        ""
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED다")
    void find_fail_notMember() throws Exception {
        // given
        when(
                recentRecordingListService.find(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        any()
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
}
