package com.knot.backend.document.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentRecordingListService;
import com.knot.backend.document.application.dto.result.DocumentRecordingDocumentResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingListResult;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
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

class DocumentRecordingControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/document-recordings";

    private DocumentRecordingListService documentRecordingListService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentRecordingListService = mock(DocumentRecordingListService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentRecordingController(documentRecordingListService))
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
    @DisplayName("녹음 묶음을 녹음 필드·문서 수·문서 항목과 다음 커서로 반환한다")
    void find_success() throws Exception {
        // given
        when(
                documentRecordingListService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        null
                )
        ).thenReturn(
                new DocumentRecordingListResult(
                        List.of(
                                new DocumentRecordingItemResult(
                                        512L,
                                        "회원 탈퇴 정책 논의",
                                        Instant.parse("2026-10-22T01:30:00Z"),
                                        1980,
                                        List.of(
                                                new DocumentRecordingDocumentResult(
                                                        301L,
                                                        "탈퇴 데이터 보존 범위",
                                                        "탈퇴 후 30일간 데이터를 보관하기로 했다."
                                                ),
                                                new DocumentRecordingDocumentResult(
                                                        302L,
                                                        "탈퇴 안내 문구",
                                                        null
                                                )
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
                .andExpect(jsonPath("$.items[0].documentCount").value(2))
                .andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].documents[0].id").value(301))
                .andExpect(jsonPath("$.items[0].documents[0].title").value("탈퇴 데이터 보존 범위"))
                .andExpect(jsonPath("$.items[0].documents[0].summary").value("탈퇴 후 30일간 데이터를 보관하기로 했다."))
                .andExpect(jsonPath("$.items[0].documents[0].length()").value(3))
                .andExpect(jsonPath("$.items[0].documents[1].summary").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    @DisplayName("cursor를 그대로 넘긴다")
    void find_success_passesCursor() throws Exception {
        // given
        when(
                documentRecordingListService.find(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        any()
                )
        ).thenReturn(
                new DocumentRecordingListResult(
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
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(documentRecordingListService).find(
                WORKSPACE_ID,
                MEMBER_ID,
                "abc"
        );
    }

    @Test
    @DisplayName("해석할 수 없는 cursor는 400 INVALID_PARAMETER다")
    void find_fail_invalidCursor() throws Exception {
        // given
        when(
                documentRecordingListService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        ""
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.INVALID_PARAMETER));

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
                documentRecordingListService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        null
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
