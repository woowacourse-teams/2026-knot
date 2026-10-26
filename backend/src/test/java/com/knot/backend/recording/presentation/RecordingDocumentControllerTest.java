package com.knot.backend.recording.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingDocumentService;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingStatus;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import com.knot.backend.recording.application.dto.result.RecordingMeetingSummaryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
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

class RecordingDocumentControllerTest {
    private static final long WORKSPACE_ID = 52L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 512L;
    private static final Instant STARTED_AT = Instant.parse("2026-10-22T01:30:00Z");
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/document";

    private RecordingDocumentService recordingDocumentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingDocumentService = mock(RecordingDocumentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingDocumentController(recordingDocumentService))
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
    @DisplayName("표시 제목·시작 시각·길이·처리 상태·재시도 정보와 회의 전체 요약만 반환한다")
    void find_success() throws Exception {
        // given
        when(
                recordingDocumentService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                new RecordingDocumentResult(
                        RECORDING_ID,
                        "회원 탈퇴 정책 논의",
                        STARTED_AT,
                        1980,
                        RecordingDocumentProcessingStatus.DOCUMENT_FAILED,
                        true,
                        List.of(
                                88L,
                                91L
                        ),
                        new RecordingMeetingSummaryResult(
                                MeetingSummaryStatus.SUCCEEDED,
                                "탈퇴 시 데이터 보존 범위를 논의했다.",
                                false
                        )
                )
        );

        // when
        ResultActions result = find(RECORDING_ID);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.title").value("회원 탈퇴 정책 논의"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-22T01:30:00Z"))
                .andExpect(jsonPath("$.recordingDurationSeconds").value(1980))
                .andExpect(jsonPath("$.processingStatus").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryableJobIds[0]").value(88))
                .andExpect(jsonPath("$.retryableJobIds[1]").value(91))
                .andExpect(jsonPath("$.meetingSummary.length()").value(3))
                .andExpect(jsonPath("$.meetingSummary.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.meetingSummary.content").value("탈퇴 시 데이터 보존 범위를 논의했다."))
                .andExpect(jsonPath("$.meetingSummary.retryable").value(false));
    }

    @Test
    @DisplayName("요약이 없으면 meetingSummary를 null로 보낸다")
    void find_success_withoutMeetingSummary() throws Exception {
        // given
        when(
                recordingDocumentService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                new RecordingDocumentResult(
                        RECORDING_ID,
                        "루덴스 님의 녹음",
                        STARTED_AT,
                        45,
                        RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED,
                        true,
                        List.of(),
                        null
                )
        );

        // when
        ResultActions result = find(RECORDING_ID);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.processingStatus").value("TRANSCRIPTION_FAILED"))
                .andExpect(jsonPath("$.retryableJobIds").isEmpty())
                .andExpect(jsonPath("$.meetingSummary").value(nullValue()));
    }

    @Test
    @DisplayName("열 수 없는 녹음은 404 RECORDING_NOT_FOUND다")
    void find_fail_recordingNotFound() throws Exception {
        // given
        when(
                recordingDocumentService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));

        // when
        ResultActions result = find(RECORDING_ID);

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자가 아닌 녹음 ID는 400 INVALID_PARAMETER다")
    void find_fail_invalidRecordingId() throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID,
                        "invalid"
                )
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(recordingDocumentService);
    }

    private ResultActions find(long recordingId) throws Exception {
        return mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID,
                        recordingId
                )
        );
    }
}
