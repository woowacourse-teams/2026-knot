package com.knot.backend.recording.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingAudioPlaybackService;
import com.knot.backend.recording.application.dto.result.PresignedAudioPlayback;
import com.knot.backend.recording.application.dto.result.RecordingAudioPlaybackResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
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

class RecordingAudioPlaybackControllerTest {
    private static final long WORKSPACE_ID = 52L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 512L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback";

    private RecordingAudioPlaybackService recordingAudioPlaybackService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingAudioPlaybackService = mock(RecordingAudioPlaybackService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingAudioPlaybackController(recordingAudioPlaybackService))
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
    @DisplayName("들을 수 있으면 재생 주소·만료 시각·형식·전체 길이를 캐시하지 않게 반환한다")
    void findPlayback_success_available() throws Exception {
        // given
        when(
                recordingAudioPlaybackService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                RecordingAudioPlaybackResult.available(
                        RECORDING_ID,
                        new PresignedAudioPlayback(
                                "https://storage.example/audio.webm?versionId=v1",
                                Instant.parse("2026-10-26T10:00:00Z")
                        ),
                        "audio/webm",
                        1980450L
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
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.recordingId").value(RECORDING_ID))
                .andExpect(jsonPath("$.audioStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.playbackUrl").value("https://storage.example/audio.webm?versionId=v1"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-26T10:00:00Z"))
                .andExpect(jsonPath("$.contentType").value("audio/webm"))
                .andExpect(jsonPath("$.recordingDurationMillis").value(1980450));
    }

    @Test
    @DisplayName("준비 중이면 재생 주소·만료 시각·형식은 null이고 전체 길이는 준다")
    void findPlayback_success_preparing() throws Exception {
        // given
        when(
                recordingAudioPlaybackService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                RecordingAudioPlaybackResult.preparing(
                        RECORDING_ID,
                        1200000L
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
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.audioStatus").value("PREPARING"))
                .andExpect(jsonPath("$.playbackUrl").isEmpty())
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.contentType").isEmpty())
                .andExpect(jsonPath("$.recordingDurationMillis").value(1200000));
    }

    @Test
    @DisplayName("음성 없음은 오류가 아니라 200 NO_AUDIO다")
    void findPlayback_success_noAudio() throws Exception {
        // given
        when(
                recordingAudioPlaybackService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                RecordingAudioPlaybackResult.noAudio(
                        RECORDING_ID,
                        2400000L
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
                .andExpect(jsonPath("$.audioStatus").value("NO_AUDIO"))
                .andExpect(jsonPath("$.playbackUrl").isEmpty());
    }

    @Test
    @DisplayName("음성 불러오기에 실패하면 500 AUDIO_STORAGE_UNAVAILABLE이다")
    void findPlayback_fail_storageUnavailable() throws Exception {
        // given
        when(
                recordingAudioPlaybackService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE));

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH,
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        );

        // then
        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIO_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("열 수 없는 녹음은 404 RECORDING_NOT_FOUND다")
    void findPlayback_fail_recordingNotFound() throws Exception {
        // given
        when(
                recordingAudioPlaybackService.find(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenThrow(new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));

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
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자가 아닌 녹음 ID는 400 INVALID_PARAMETER다")
    void findPlayback_fail_invalidRecordingId() throws Exception {
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
        verifyNoInteractions(recordingAudioPlaybackService);
    }
}
