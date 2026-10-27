package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class LlmMeetingSummaryGeneratorTest {

    private final LlmClient client = mock(LlmClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmMeetingSummaryGenerator generator = new LlmMeetingSummaryGenerator(
            client,
            mapper
    );

    @Test
    void generate_splitsAfterContextLimitAndMerges() {
        String input = "첫 구간 논의입니다.\n".repeat(1000) + "마지막 결정입니다.";
        String finalResponse = "{\"content\":\"## 한눈에 보기\\n결정을 정리한다.\\n회의 핵심 결론: 합의했다.\"}";
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫 구간 논의\"}")
                .thenReturn("{\"content\":\"마지막 결정\"}")
                .thenReturn(finalResponse);
        assertThat(generator.generate(input)).contains("회의 핵심 결론:");
        ArgumentCaptor<LlmCompletionRequest> requests = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(
                client,
                times(4)
        ).complete(requests.capture());
        String first = mapper.readTree(
                requests.getAllValues()
                        .get(1)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        String second = mapper.readTree(
                requests.getAllValues()
                        .get(2)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        assertThat(first + second).isEqualTo(input);
    }

    @Test
    void generate_doesNotSplitOnAuthenticationFailure() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_AUTHENTICATION_FAILED));
        assertThatThrownBy(() -> generator.generate("원문")).isInstanceOf(LlmException.class);
        verify(
                client,
                times(1)
        ).complete(any());
    }

    @Test
    void generate_stopsWhenProviderAlwaysRejectsInput() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED));
        assertThatThrownBy(() -> generator.generate("한😀글 회의 원문")).isInstanceOf(LlmException.class);
    }

    @Test
    void generate_preservesUnicodeWhenSplittingAndRejectsInvalidIntermediateResponse() {
        String input = "가😀나".repeat(30);
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫 구간\"}")
                .thenReturn("{\"content\":null}");
        assertThatThrownBy(() -> generator.generate(input)).isInstanceOf(LlmException.class);
        ArgumentCaptor<LlmCompletionRequest> requests = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(
                client,
                times(3)
        ).complete(requests.capture());
        String first = mapper.readTree(
                requests.getAllValues()
                        .get(1)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        String second = mapper.readTree(
                requests.getAllValues()
                        .get(2)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        assertThat(first + second).isEqualTo(input);
        assertThat(Character.isHighSurrogate(first.charAt(first.length() - 1))).isFalse();
        assertThat(Character.isLowSurrogate(second.charAt(0))).isFalse();
    }

    @Test
    void generate_recursivelySplitsRejectedPart() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫째\"}")
                .thenReturn("{\"content\":\"둘째\"}")
                .thenReturn("{\"content\":\"셋째\"}")
                .thenReturn("{\"content\":\"## 한눈에 보기\\n전체 정리\\n회의 핵심 결론: 합의했다.\"}");
        assertThat(generator.generate("앞부분 중간부분 끝부분")).contains("회의 핵심 결론:");
        verify(
                client,
                times(6)
        ).complete(any());
    }

    @Test
    void generate_reducesIntermediateResultsWhenFinalMergeExceedsContext() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫 번째 구간에서 논의한 상세한 내용입니다.\"}")
                .thenReturn("{\"content\":\"두 번째 구간에서 논의한 상세한 내용입니다.\"}")
                .thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫 결정\"}")
                .thenReturn("{\"content\":\"둘째 조건\"}")
                .thenReturn("{\"content\":\"## 한눈에 보기\\n결정과 조건\\n회의 핵심 결론: 조건부로 진행한다.\"}");
        assertThat(generator.generate("앞부분 중간부분 끝부분")).contains("조건부로 진행한다");
        verify(
                client,
                times(7)
        ).complete(any());
    }

    @Test
    void generate_splitsStoredWhisperJsonByUtteranceText() {
        String input = "{\"transcription\":[{\"text\":\"첫 발화: \\\"동의\\\"\"},{\"text\":\"두 번째 발화\"}]}";
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫 발화\"}")
                .thenReturn("{\"content\":\"두 번째 발화\"}")
                .thenReturn("{\"content\":\"## 한눈에 보기\\n논의 정리\\n회의 핵심 결론: 논의했다.\"}");
        generator.generate(input);
        ArgumentCaptor<LlmCompletionRequest> requests = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(
                client,
                times(4)
        ).complete(requests.capture());
        String first = mapper.readTree(
                requests.getAllValues()
                        .get(1)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        String second = mapper.readTree(
                requests.getAllValues()
                        .get(2)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        assertThat(first + second).isEqualTo("첫 발화: \"동의\"\n두 번째 발화");
    }

    @Test
    void generate_rejectsStoredWhisperJsonWithMissingTextWithoutDroppingSegment() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED));
        String input = "{\"transcription\":[{\"text\":\"유효\"},{\"text\":null}]}";
        assertThatThrownBy(() -> generator.generate(input)).isInstanceOf(LlmException.class);
        verify(
                client,
                times(1)
        ).complete(any());
    }

    @Test
    void generate_doesNotDiscardTrailingContentAfterJson() {
        String input = "{\"transcription\":[{\"text\":\"앞\"}]} {\"text\":\"뒤\"}";
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn("{\"content\":\"첫째\"}")
                .thenReturn("{\"content\":\"둘째\"}")
                .thenReturn("{\"content\":\"## 한눈에 보기\\n내용\\n회의 핵심 결론: 논의했다.\"}");
        generator.generate(input);
        ArgumentCaptor<LlmCompletionRequest> requests = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(
                client,
                times(4)
        ).complete(requests.capture());
        String first = mapper.readTree(
                requests.getAllValues()
                        .get(1)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        String second = mapper.readTree(
                requests.getAllValues()
                        .get(2)
                        .messages()
                        .get(1)
                        .content()
        )
                .path("transcriptText")
                .asString();
        assertThat(first + second).isEqualTo(input);
    }

    @Test
    @DisplayName("전체 원문을 JSON으로 보내고 content 하나의 완성된 요약만 반환한다")
    void generate_success_fullJsonInput() {
        // given
        String transcript = "원문 \\\"인용\\\"\n".repeat(2500);
        when(client.complete(any())).thenReturn("{\"content\":\"## 한눈에 보기\\n정책을 유지한다.\\n회의 핵심 결론: 정책을 유지한다.\"}");
        // when
        String content = generator.generate(transcript);
        // then
        assertThat(content).startsWith("## 한눈에 보기");
        ArgumentCaptor<LlmCompletionRequest> captor = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(client).complete(captor.capture());
        LlmCompletionRequest request = captor.getValue();
        assertThat(
                mapper.readTree(
                        request.messages()
                                .get(1)
                                .content()
                )
                        .path("transcriptText")
                        .asString()
        ).isEqualTo(transcript);
        assertThat(request.schemaName()).isEqualTo("meeting_summary");
        assertThat(
                request.messages()
                        .getFirst()
                        .content()
        ).doesNotContain("아무도 반대하지 않은 것")
                .contains("반대 발언이 없다는 이유만으로");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "{\"content\":null}", "{\"content\":123}", "{\"content\":\"\\u00a0\"}",
            "{\"content\":\"요약\"}", "{\"content\":\"## 한눈에 보기\\n내용\",\"extra\":1}",
            "{\"content\":\"## 한눈에 보기\\n내용\"} {}", "{\"content\":\"## 한눈에 보기\\n내용\",\"content\":\"다른 내용\"}"})
    @DisplayName("잘못된 JSON이나 불완전한 요약은 성공 결과로 반환하지 않는다")
    void generate_failure_invalidResponse(String response) {
        // given
        when(client.complete(any())).thenReturn(response);
        // when & then
        assertThatThrownBy(() -> generator.generate("원문")).isInstanceOfAny(
                RecordingException.class,
                LlmException.class
        );
    }
}
