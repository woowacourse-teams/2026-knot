package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LlmRecordingTitleGeneratorTest {

    @Mock
    private LlmClient client;
    private LlmRecordingTitleGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new LlmRecordingTitleGenerator(
                client,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("전사로 짧은 회의 제목을 요청하고 응답의 제목을 돌려준다")
    void generate_success() {
        // given
        when(client.complete(any())).thenReturn("{\"title\":\"회원 탈퇴 정책 논의\"}");
        ArgumentCaptor<LlmCompletionRequest> request = ArgumentCaptor.forClass(LlmCompletionRequest.class);

        // when
        String title = generator.generate("회원 탈퇴 정책을 정했다.");

        // then
        assertThat(title).isEqualTo("회원 탈퇴 정책 논의");
        verify(client).complete(request.capture());
        assertThat(
                request.getValue()
                        .schemaName()
        ).isEqualTo("recording_title");
        assertThat(
                request.getValue()
                        .timeout()
        ).isEqualTo(Duration.ofSeconds(30));
        assertThat(
                request.getValue()
                        .messages()
        ).hasSize(2);
        assertThat(
                request.getValue()
                        .messages()
                        .getLast()
                        .content()
        ).isEqualTo("회원 탈퇴 정책을 정했다.");
    }

    @Test
    @DisplayName("긴 전사는 앞부분만 보내 제목 요청 크기를 제한한다")
    void generate_success_truncatesTranscript() {
        // given
        when(client.complete(any())).thenReturn("{\"title\":\"긴 회의\"}");
        ArgumentCaptor<LlmCompletionRequest> request = ArgumentCaptor.forClass(LlmCompletionRequest.class);

        // when
        generator.generate("가".repeat(LlmRecordingTitleGenerator.MAX_INPUT_CHARACTERS + 10));

        // then
        verify(client).complete(request.capture());
        assertThat(
                request.getValue()
                        .messages()
                        .getLast()
                        .content()
        ).hasSize(LlmRecordingTitleGenerator.MAX_INPUT_CHARACTERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"title\":1}", "{\"title\":\"제목\",\"extra\":\"x\"}",
            "{\"title\":\"제목\"} {}", "[\"제목\"]"})
    @DisplayName("약속한 형식이 아닌 응답은 잘못된 응답으로 거절한다")
    void generate_fail_invalidResponse(String response) {
        // given
        when(client.complete(any())).thenReturn(response);

        // when & then
        assertThatThrownBy(() -> generator.generate("회의")).isInstanceOf(LlmException.class)
                .extracting("errorCode")
                .isEqualTo(LlmErrorCode.LLM_INVALID_RESPONSE);
    }

    @Test
    @DisplayName("외부 호출 실패는 그대로 알려 작업이 재시도를 판단하게 한다")
    void generate_fail_clientFailure() {
        // given
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));

        // when & then
        assertThatThrownBy(() -> generator.generate("회의")).isInstanceOf(LlmException.class)
                .extracting("errorCode")
                .isEqualTo(LlmErrorCode.LLM_TIMEOUT);
    }
}
