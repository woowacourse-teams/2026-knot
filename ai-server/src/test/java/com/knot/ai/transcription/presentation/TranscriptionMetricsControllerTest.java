package com.knot.ai.transcription.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.global.AppAuthenticationFilter;
import com.knot.ai.transcription.application.TranscriptionEngine;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.TranscriptionMetrics;
import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptionMetricsControllerTest {
    private TranscriptionJobService service;
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        TranscriptionEngine engine = mock(TranscriptionEngine.class);
        when(engine.isReady()).thenReturn(true);
        service = new TranscriptionJobService(
                engine,
                new TranscriptionMetrics()
        );
        AiServerProperties properties = mock(AiServerProperties.class);
        when(properties.token()).thenReturn("test-token");
        mvc = MockMvcBuilders.standaloneSetup(new TranscriptionMetricsController(service))
                .addFilters(new AppAuthenticationFilter(properties))
                .build();
    }

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    @DisplayName("지표도 인증 헤더가 없으면 기존 필터가 거절한다")
    void getMetrics_failure_noAuthentication() throws Exception {
        // when
        ResultActions response = mvc.perform(get("/internal/metrics"));

        // then
        response.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("잘못된 인증 토큰으로는 지표를 읽을 수 없다")
    void getMetrics_failure_invalidAuthentication() throws Exception {
        // when
        ResultActions response = mvc.perform(
                get("/internal/metrics").header(
                        "Authorization",
                        "Bearer incorrect"
                )
        );

        // then
        response.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증된 지표 응답은 Prometheus 포맷과 제한된 라벨만 노출한다")
    void getMetrics_success_safePrometheusText() throws Exception {
        // given
        UUID attempt = UUID.randomUUID();
        service.create(
                new CreateTranscriptionCommand(
                        attempt,
                        "https://private.invalid?token=secret",
                        null
                )
        );

        // when
        MvcResult response = mvc.perform(
                get("/internal/metrics").header(
                        "Authorization",
                        "Bearer test-token"
                )
        )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(
                        header().string(
                                "Content-Type",
                                containsString("version=0.0.4")
                        )
                )
                .andReturn();

        // then
        String body = response.getResponse()
                .getContentAsString();
        assertThat(body).endsWith("\n")
                .contains("# TYPE knot_ai_jobs_completed_total counter\n")
                .doesNotContain(
                        attempt.toString(),
                        "private.invalid",
                        "test-token",
                        "requestId",
                        "attemptId",
                        "userId"
                );
    }
}
