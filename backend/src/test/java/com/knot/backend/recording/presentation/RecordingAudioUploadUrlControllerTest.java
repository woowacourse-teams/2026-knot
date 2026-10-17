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
import com.knot.backend.recording.application.RecordingAudioUploadService;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import java.time.Instant;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordingAudioUploadUrlControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url";
    private static final String BODY = "{\"contentType\":\"audio/webm\",\"contentLength\":1024}";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-05T00:15:00Z");

    private RecordingAudioUploadService recordingAudioUploadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingAudioUploadService = mock(RecordingAudioUploadService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingAudioUploadUrlController(recordingAudioUploadService))
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
    @DisplayName("새 업로드를 예약하면 201과 uploadId, URL, 만료 시각을 반환한다")
    void issue_success_created() throws Exception {
        // given
        stubResult(true);

        // when
        ResultActions result = mockMvc.perform(request());

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(5))
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.example/recordings/34/key"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-05T00:15:00Z"));
    }

    @Test
    @DisplayName("기존 예약의 URL을 다시 발급하면 200을 반환한다")
    void issue_success_reissued() throws Exception {
        // given
        stubResult(false);

        // when
        ResultActions result = mockMvc.perform(request());

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(5));
    }

    @Test
    @DisplayName("Content-Type이 없으면 VALIDATION_ERROR 400을 반환하고 서비스를 호출하지 않는다")
    void issue_failure_missingContentType() throws Exception {
        // given
        String body = """
                {"contentLength":1024}
                """;

        // when
        ResultActions result = mockMvc.perform(
                post(
                        PATH,
                        WORKSPACE_ID,
                        RECORDING_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("contentType"));
        verifyNoInteractions(recordingAudioUploadService);
    }

    private void stubResult(boolean created) {
        when(
                recordingAudioUploadService.issue(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(
                new RecordingAudioUploadUrlResult(
                        5L,
                        "https://storage.example/recordings/34/key",
                        EXPIRES_AT,
                        created
                )
        );
    }

    private RequestBuilder request() {
        return post(
                PATH,
                WORKSPACE_ID,
                RECORDING_ID
        ).contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
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
