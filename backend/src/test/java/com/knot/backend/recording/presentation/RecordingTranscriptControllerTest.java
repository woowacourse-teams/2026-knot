package com.knot.backend.recording.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingTranscriptService;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
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

class RecordingTranscriptControllerTest {
    private static final long WORKSPACE_ID = 52L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 512L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript";

    private RecordingTranscriptService recordingTranscriptService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingTranscriptService = mock(RecordingTranscriptService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingTranscriptController(recordingTranscriptService))
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
    @DisplayName("녹음 ID·전사 ID·전체 원문·구간만 반환한다")
    void find_success() throws Exception {
        // given
        when(
                recordingTranscriptService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                new RecordingTranscriptResult(
                        RECORDING_ID,
                        81L,
                        "지난주에 이야기한 탈퇴 정책부터 정리할게요.",
                        List.of(
                                new TranscriptSegmentResult(
                                        160000,
                                        164000L,
                                        1,
                                        "지난주에 이야기한 탈퇴 정책부터 정리할게요."
                                )
                        )
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.transcriptId").value(81))
                .andExpect(jsonPath("$.transcriptText").value("지난주에 이야기한 탈퇴 정책부터 정리할게요."))
                .andExpect(jsonPath("$.segments[0].length()").value(4))
                .andExpect(jsonPath("$.segments[0].startMillis").value(160000))
                .andExpect(jsonPath("$.segments[0].endMillis").value(164000))
                .andExpect(jsonPath("$.segments[0].speakerNumber").value(1))
                .andExpect(jsonPath("$.segments[0].text").value("지난주에 이야기한 탈퇴 정책부터 정리할게요."));
    }

    @Test
    @DisplayName("보여 줄 전사가 없으면 404 RECORDING_TRANSCRIPT_NOT_FOUND다")
    void find_fail_transcriptNotFound() throws Exception {
        // given
        when(
                recordingTranscriptService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_TRANSCRIPT_NOT_FOUND));

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        );

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_TRANSCRIPT_NOT_FOUND"));
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
        verifyNoInteractions(recordingTranscriptService);
    }
}
