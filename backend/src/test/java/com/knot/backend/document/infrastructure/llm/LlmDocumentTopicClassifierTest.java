package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentTopic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LlmDocumentTopicClassifierTest {

    @Mock
    private LlmClient client;
    private LlmDocumentTopicClassifier classifier;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        classifier = new LlmDocumentTopicClassifier(
                new DocumentTopicPrompt(mapper),
                client,
                mapper
        );
    }

    @Test
    @DisplayName("복수 주제를 처음 등장한 순서의 불변 목록으로 반환한다")
    void classify_success_multipleTopics() {
        // given
        when(client.complete(any())).thenReturn("{\"topics\":[\"알림 문구\",\"검색 도입\"]}");

        // when
        List<DocumentTopic> topics = classifier.classify("알림 문구를 바꾸자. 문서가 늘면 검색도 필요하다.");

        // then
        assertThat(topics).extracting(DocumentTopic::value)
                .containsExactly(
                        "알림 문구",
                        "검색 도입"
                );
        assertThatThrownBy(() -> topics.add(DocumentTopic.of("다른 주제")))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(client).complete(any());
    }

    @Test
    @DisplayName("명시적인 정상 빈 배열만 내용 없음으로 반환한다")
    void classify_success_noContent() {
        // given
        when(client.complete(any())).thenReturn("{\"topics\":[]}");

        // when
        List<DocumentTopic> topics = classifier.classify("소리 들리나요? 네.");

        // then
        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("Unicode 공백과 NFC를 정규화하고 정확히 같은 주제만 제거한다")
    void classify_success_normalizesTopics() {
        // given
        when(client.complete(any())).thenReturn("""
                {"topics":["　알림\\u00a0  문구　","알림 문구","가","가","검색 도입","검색 기능 개발"]}
                """);

        // when
        List<DocumentTopic> topics = classifier.classify("유효한 논의");

        // then
        assertThat(topics).extracting(DocumentTopic::value)
                .containsExactly(
                        "알림 문구",
                        "가",
                        "검색 도입",
                        "검색 기능 개발"
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "null", "[]", "{}", "{\"topics\":null}", "{\"topics\":\"검색\"}",
            "{\"topics\":[1]}", "{\"topics\":[null]}", "{\"topics\":[\"검색\", \"　\\u00a0\"]}",
            "{\"topics\":[],\"extra\":true}", "{\"topics\":[]} {\"topics\":[\"검색\"]}",
            "{\"topics\":[],\"topics\":[\"검색\"]}"})
    @DisplayName("누락·타입 오류·공백 주제·추가 JSON을 내용 없음으로 숨기지 않는다")
    void classify_failure_invalidResponse(String response) {
        // given
        when(client.complete(any())).thenReturn(response);

        // when & then
        assertThatThrownBy(() -> classifier.classify("유효한 논의")).isInstanceOf(DocumentException.class)
                .hasMessage(DocumentErrorCode.INVALID_TOPIC_CLASSIFICATION_RESPONSE.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = LlmErrorCode.class, names = {"LLM_TIMEOUT", "LLM_INPUT_LIMIT_EXCEEDED"})
    @DisplayName("공급자 실패를 빈 배열로 바꾸지 않고 호출자에게 전달한다")
    void classify_failure_providerError(LlmErrorCode errorCode) {
        // given
        LlmException failure = new LlmException(errorCode);
        when(client.complete(any())).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> classifier.classify("유효한 논의")).isSameAs(failure);
    }
}
