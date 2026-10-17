package com.knot.backend.recording.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.exception.GlobalExceptionHandler;
import com.knot.backend.recording.application.RecordingAudioChunkService;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import com.knot.backend.recording.application.dto.result.RecordingAudioSaveResult;
import com.knot.backend.recording.application.dto.result.AudioFrameRange;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.AudioSequenceGapException;
import com.knot.backend.recording.presentation.dto.request.RecordingAudioChunkRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioChunkReservationResponse;
import java.util.Map;
import java.util.UUID;
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

class RecordingAudioChunkControllerTest {
    private static final long WORKSPACE_ID = 12L;
    private static final long MEMBER_ID = 1L;
    private static final long RECORDING_ID = 34L;
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/recordings/{recordingId}";
    private static final String CONTROL_TOKEN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA";
    private static final UUID TAB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CHECKSUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-17T00:15:00Z");

    private RecordingAudioChunkService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RecordingAudioChunkService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingAudioChunkController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                AuthenticatedMember.of(
                                        MEMBER_ID,
                                        "octocat",
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
    @DisplayName("새 조각 예약은 201과 조건부 PUT 헤더를 반환한다")
    void reserve_created() throws Exception {
        // given
        stubReservation(true);

        // when
        ResultActions result = mockMvc.perform(reserveRequest(body()));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.example/upload"))
                .andExpect(jsonPath("$.requiredHeaders['if-none-match']").value("*"))
                .andExpect(jsonPath("$.requiredHeaders['x-amz-checksum-sha256']").value(CHECKSUM));
    }

    @Test
    @DisplayName("동일 예약을 재요청하면 200이다")
    void reserve_reissued() throws Exception {
        // given
        stubReservation(false);

        // when
        ResultActions result = mockMvc.perform(reserveRequest(body()));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1));
    }

    @Test
    @DisplayName("이미 검증된 조각은 URL 없이 같은 VERIFIED를 반환한다")
    void reserve_verified() throws Exception {
        // given
        when(
                service.reserve(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(
                new RecordingAudioChunkReservationResult(
                        false,
                        1,
                        RecordingAudioChunkStatus.VERIFIED,
                        null,
                        null,
                        Map.of()
                )
        );

        // when
        ResultActions result = mockMvc.perform(reserveRequest(body()));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.uploadUrl").value(nullValue()))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @Test
    @DisplayName("필수 descriptor가 없으면 400이며 서비스를 호출하지 않는다")
    void reserve_missingDescriptor() throws Exception {
        // given
        String requestBody = "{\"tabId\":\"" + TAB_ID + "\",\"controlToken\":\"" + CONTROL_TOKEN + "\"}";

        // when
        ResultActions result = mockMvc.perform(reserveRequest(requestBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("frame 수와 byte 수가 다르면 400이며 서비스를 호출하지 않는다")
    void reserve_invalidDescriptor() throws Exception {
        // given
        String requestBody = body().replace(
                "160000",
                "159999"
        );

        // when
        ResultActions result = mockMvc.perform(reserveRequest(requestBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_CHUNK"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("sequence gap은 409와 다음 기대 번호를 반환한다")
    void reserve_sequenceGap() throws Exception {
        // given
        when(
                service.reserve(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenThrow(new AudioSequenceGapException(2));

        // when
        ResultActions result = mockMvc.perform(reserveRequest(body()));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIO_SEQUENCE_GAP"))
                .andExpect(jsonPath("$.expectedNextSequence").value(2));
    }

    @Test
    @DisplayName("완료 응답은 연속 저장 범위와 확인 시각을 반환한다")
    void complete_schema() throws Exception {
        // given
        when(
                service.complete(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        eq(1),
                        any()
                )
        ).thenReturn(
                new RecordingAudioChunkCompletionResult(
                        1,
                        RecordingAudioChunkStatus.VERIFIED,
                        1,
                        80000,
                        5000,
                        EXPIRES_AT
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                post(
                        PATH + "/audio-chunks/{sequence}/complete",
                        WORKSPACE_ID,
                        RECORDING_ID,
                        1
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(controlBody())
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedThroughSequence").value(1))
                .andExpect(jsonPath("$.verifiedThroughFrame").value(80000))
                .andExpect(jsonPath("$.verifiedAudioMillis").value(5000))
                .andExpect(jsonPath("$.confirmedAt").value("2026-10-17T00:15:00Z"));
    }

    @Test
    @DisplayName("저장 상태는 별도 검증 구간과 연속 watermark를 구분한다")
    void findState_schema() throws Exception {
        // given
        when(
                service.findState(
                        WORKSPACE_ID,
                        MEMBER_ID,
                        RECORDING_ID
                )
        ).thenReturn(
                new RecordingAudioSaveResult(
                        "CAPTURING",
                        0,
                        0,
                        0,
                        List.of(
                                new AudioFrameRange(
                                        80000,
                                        160000
                                )
                        ),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null
                )
        );

        // when
        ResultActions result = mockMvc.perform(
                get(
                        PATH + "/audio-save",
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        );

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedThroughFrame").value(0))
                .andExpect(jsonPath("$.verifiedRanges[0].start").value(80000))
                .andExpect(jsonPath("$.verifiedRanges[0].endExclusive").value(160000))
                .andExpect(jsonPath("$.tailUnknown").value(false))
                .andExpect(jsonPath("$.canRetry").value(false));
    }

    @Test
    @DisplayName("조각 요청과 응답 문자열은 제어 증명과 서명 URL을 드러내지 않는다")
    void dto_redactsSecrets() {
        // given
        RecordingAudioChunkRequest request = new RecordingAudioChunkRequest(
                TAB_ID,
                CONTROL_TOKEN,
                1,
                0L,
                80000,
                160000L,
                CHECKSUM
        );
        RecordingAudioChunkReservationResult result = reservation(true);

        // when
        String rendered = request + " " + result + " " + RecordingAudioChunkReservationResponse.from(result);

        // then
        assertThat(rendered).doesNotContain(CONTROL_TOKEN)
                .doesNotContain("https://storage.example/upload");
    }

    private void stubReservation(boolean created) {
        when(
                service.reserve(
                        eq(WORKSPACE_ID),
                        eq(MEMBER_ID),
                        eq(RECORDING_ID),
                        any()
                )
        ).thenReturn(reservation(created));
    }

    private RecordingAudioChunkReservationResult reservation(boolean created) {
        return new RecordingAudioChunkReservationResult(
                created,
                1,
                RecordingAudioChunkStatus.RESERVED,
                "https://storage.example/upload",
                EXPIRES_AT,
                Map.of(
                        "if-none-match",
                        "*",
                        "x-amz-checksum-sha256",
                        CHECKSUM
                )
        );
    }

    private RequestBuilder reserveRequest(String body) {
        return post(
                PATH + "/audio-chunks",
                WORKSPACE_ID,
                RECORDING_ID
        ).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String body() {
        return """
                {"tabId":"%s","controlToken":"%s","sequence":1,"sampleOffset":0,
                 "frameCount":80000,"byteLength":160000,"checksumSha256":"%s"}
                """.formatted(
                TAB_ID,
                CONTROL_TOKEN,
                CHECKSUM
        );
    }

    private String controlBody() {
        return """
                {"tabId":"%s","controlToken":"%s"}
                """.formatted(
                TAB_ID,
                CONTROL_TOKEN
        );
    }
}
