package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.global.exception.LlmException;
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
