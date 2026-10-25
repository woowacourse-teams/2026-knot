package com.knot.ai.transcription.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.global.AppAuthenticationFilter;
import com.knot.ai.global.GlobalExceptionHandler;
import com.knot.ai.transcription.application.JobSnapshot;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import com.knot.ai.transcription.domain.DecodePolicy;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import com.knot.ai.transcription.domain.WindowInput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class TranscriptionJobControllerTest {
    private MockMvc mvc;
    private TranscriptionJobService service;

    @BeforeEach
    void prepare() {
        service = mock(TranscriptionJobService.class);
        AiServerProperties properties = mock(AiServerProperties.class);
        when(properties.token()).thenReturn("test-token");
        mvc = MockMvcBuilders.standaloneSetup(new TranscriptionJobController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AppAuthenticationFilter(properties))
                .build();
    }

    @Test
    @DisplayName("인증 없는 요청은 기존 토큰 실패 응답 형식을 유지한다")
    void getHealth_failure_noAuthentication() throws Exception {
        // when
        ResultActions response = mvc.perform(get("/health"));

        // then
        response.andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"앱 인증이 필요합니다\"}"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("인증된 health 응답은 준비 상태만 반환한다")
    void getHealth_success() throws Exception {
        // given
        when(service.isReady()).thenReturn(true);

        // when
        ResultActions response = mvc.perform(authorized(get("/health")));

        // then
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.profileId").doesNotExist());
    }

    @Test
    @DisplayName("모델 실행 준비가 되지 않으면 health는 503을 반환한다")
    void getHealth_failure_notReady() throws Exception {
        // when
        ResultActions response = mvc.perform(authorized(get("/health")));

        // then
        response.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    @DisplayName("다른 작업이 실행 중이면 대기열 없이 충돌을 반환한다")
    void create_failure_busy() throws Exception {
        // given
        when(service.create(any())).thenThrow(new TranscriptionException(TranscriptionErrorCode.BUSY));

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID()))
        );

        // then
        response.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSY"));
    }

    @Test
    @DisplayName("수락된 작업은 attempt ID로 조회 가능한 위치와 기존 상태 필드를 반환한다")
    void create_success_accepted() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.create(any())).thenReturn(
                new JobSnapshot(
                        attemptId,
                        JobSnapshot.Status.PROCESSING,
                        null,
                        null
                )
        );

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(attemptId))
        );

        // then
        response.andExpect(status().isAccepted())
                .andExpect(
                        header().string(
                                "Location",
                                "/api/v1/transcription-jobs/" + attemptId
                        )
                )
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(service).create(
                new CreateTranscriptionCommand(
                        attemptId,
                        "https://audio",
                        null
                )
        );
    }

    @Test
    @DisplayName("완료 작업 조회 응답은 원본 전사 JSON을 객체로 풀지 않고 문자열로 보존한다")
    void get_success_succeededResult() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        String rawWhisperJson = "{\"transcription\":[{\"text\":\"안녕\"}]}";
        when(service.get(attemptId)).thenReturn(
                new JobSnapshot(
                        attemptId,
                        JobSnapshot.Status.SUCCEEDED,
                        null,
                        new TranscriptResult(
                                1,
                                rawWhisperJson,
                                List.of(
                                        new TranscriptResult.Utterance(
                                                0,
                                                "화자 1",
                                                0,
                                                1,
                                                "안녕"
                                        )
                                )
                        )
                )
        );

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.result.schemaVersion").value(1))
                .andExpect(jsonPath("$.result.rawWhisperJson").value(rawWhisperJson))
                .andExpect(jsonPath("$.result.utterances[0].ord").value(0))
                .andExpect(jsonPath("$.result.utterances[0].speakerLabel").value("화자 1"))
                .andExpect(jsonPath("$.result.utterances[0].startSec").value(0.0))
                .andExpect(jsonPath("$.result.utterances[0].endSec").value(1.0))
                .andExpect(jsonPath("$.result.utterances[0].text").value("안녕"));
    }

    @Test
    @DisplayName("삭제 API는 지원하지 않는다")
    void delete_failure_notSupported() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();

        // when
        ResultActions response = mvc.perform(authorized(delete("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("형식이 잘못된 생성 요청은 한글 검증 오류를 반환한다")
    void create_failure_invalidRequest() throws Exception {
        // given
        String body = "{}";

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("화자 수는 양수만 허용한다")
    void create_failure_invalidSpeakerCount() throws Exception {
        // given
        String body = """
                {"attemptId":"%s","downloadUrl":"%s","speakerCount":0}
                """.formatted(
                UUID.randomUUID(),
                "https://audio"
        );

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("speakerCount"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("화자 수는 양수여야 합니다"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("잘못된 UUID 경로 변수는 내부 예외명 없이 거부한다")
    void get_failure_invalidUuid() throws Exception {
        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/not-a-uuid")));

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"))
                .andExpect(content().string(not(containsString("MethodArgumentTypeMismatchException"))));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("깨진 JSON 본문은 내부 파서 메시지 없이 거부한다")
    void create_failure_invalidJsonBody() throws Exception {
        // given
        String body = "{";

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"))
                .andExpect(content().string(not(containsString("JSON parse error"))));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("알 수 없는 작업은 내부 오류 없이 찾을 수 없음으로 응답한다")
    void get_failure_unknownJob() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.get(attemptId)).thenThrow(new TranscriptionException(TranscriptionErrorCode.JOB_NOT_FOUND));

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("전사 작업을 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 내부 메시지를 가리고 공통 오류로 응답한다")
    void get_failure_unexpectedErrorRedacted(CapturedOutput output) throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.get(attemptId)).thenThrow(
                new IllegalStateException(
                        "boom secret",
                        new IllegalArgumentException("cause secret")
                )
        );

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("요청을 처리할 수 없습니다"))
                .andExpect(content().string(not(containsString("boom secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
        assertThat(output.getAll()).contains(
                "attemptId=" + attemptId,
                "code=INTERNAL_ERROR",
                "exception=java.lang.IllegalStateException",
                "cause=java.lang.IllegalArgumentException",
                "location="
        )
                .doesNotContain(
                        "boom secret",
                        "cause secret"
                );
    }

    @Test
    @DisplayName("잘못된 HTTP 방식은 내부 예외 없이 거부한다")
    void getHealth_failure_invalidMethod() throws Exception {
        // when
        ResultActions response = mvc.perform(authorized(post("/health")));

        // then
        response.andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("잘못된 인증 토큰은 작업 접수 전에 거부한다")
    void create_failure_invalidAuthentication() throws Exception {
        // given
        String body = createBody(UUID.randomUUID());

        // when
        ResultActions response = mvc.perform(
                post("/api/v1/transcription-jobs").header(
                        "Authorization",
                        "Bearer sensitive-invalid-token"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("sensitive-invalid-token"))));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("준비되지 않은 서버는 접수에 503 오류를 반환한다")
    void create_failure_notReady() throws Exception {
        // given
        when(service.create(any())).thenThrow(new TranscriptionException(TranscriptionErrorCode.NOT_READY));

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID()))
        );

        // then
        response.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NOT_READY"))
                .andExpect(jsonPath("$.message").value("전사 서버가 준비되지 않았습니다"));
    }

    @Test
    @DisplayName("구간 요청의 디코딩 정책을 Command에 그대로 전달한다")
    void create_success_windowDecodePolicy() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.create(any())).thenReturn(
                new JobSnapshot(
                        attemptId,
                        JobSnapshot.Status.PROCESSING,
                        null,
                        null
                )
        );

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(
                                windowBody(
                                        attemptId,
                                        "\"WITH_VOCABULARY\""
                                )
                        )
        );

        // then
        response.andExpect(status().isAccepted());
        verify(service).create(
                new CreateTranscriptionCommand(
                        attemptId,
                        "https://audio",
                        null,
                        new WindowInput(
                                "pcm-window-v1",
                                "a".repeat(64),
                                0,
                                2_080_000,
                                DecodePolicy.WITH_VOCABULARY
                        )
                )
        );
    }

    @Test
    @DisplayName("알 수 없는 디코딩 정책은 내부 예외명 없이 거부한다")
    void create_failure_unknownDecodePolicy() throws Exception {
        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(
                                windowBody(
                                        UUID.randomUUID(),
                                        "\"TWICE\""
                                )
                        )
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(content().string(not(containsString("DecodePolicy"))));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header(
                "Authorization",
                "Bearer test-token"
        );
    }

    private String createBody(UUID attemptId) {
        return """
                {"attemptId":"%s","downloadUrl":"%s","speakerCount":null}
                """.formatted(
                attemptId,
                "https://audio"
        );
    }

    private String windowBody(
            UUID attemptId,
            String decodePolicy
    ) {
        return """
                {"attemptId":"%s","downloadUrl":"https://audio","window":{"pipelineVersion":"pcm-window-v1",\
                "inputManifestSha256":"%s","inputStartFrame":0,"inputEndFrame":2080000,"decodePolicy":%s}}
                """.formatted(
                attemptId,
                "a".repeat(64),
                decodePolicy
        );
    }
}
