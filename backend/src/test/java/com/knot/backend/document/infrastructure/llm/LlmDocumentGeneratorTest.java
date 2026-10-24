package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentGenerationInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class LlmDocumentGeneratorTest {

    @Mock
    private LlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private LlmDocumentGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new LlmDocumentGenerator(
                new DocumentGenerationPrompt(mapper),
                client,
                mapper,
                new DocumentMarkdownValidator()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"요약", "", "　\u00a0"})
    @NullSource
    @DisplayName("제목 바깥 공백만 정리하고 Markdown 내부 형식을 보존하며 빈 요약은 null로 만든다")
    void generate_success_nullableSummary(String summary) {
        // given
        String content = "## 핵심 요약\n검색을 논의했다.\n\n## 배경\n문서가  많아졌다.\n";
        when(client.complete(any())).thenReturn(
                mapper.writeValueAsString(
                        mapper.createObjectNode()
                                .put(
                                        "title",
                                        "　 검색 논의 \u00a0"
                                )
                                .put(
                                        "summary",
                                        summary
                                )
                                .put(
                                        "content",
                                        content
                                )
                )
        );

        // when
        DocumentGenerationResult result = generator.generate(
                DocumentGenerationInput.of(
                        "검색하자",
                        "검색"
                )
        );

        // then
        assertThat(result.title()).isEqualTo("검색 논의");
        assertThat(result.content()).isEqualTo(content);
        String expectedSummary = expectedSummary(summary);
        assertThat(result.summary()).isEqualTo(expectedSummary);
        assertThat(result.toString()).doesNotContain(
                "검색",
                "문서가"
        );
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "　", "not json", "[]", "null", "{}", "{\"title\":\"제목\",\"content\":\"## 핵심 요약\\n내용\"}",
            "{\"title\":1,\"summary\":null,\"content\":\"본문\"}", "{\"title\":\"제목\",\"summary\":[],\"content\":\"본문\"}",
            "{\"title\":\"제목\",\"summary\":false,\"content\":\"본문\"}",
            "{\"title\":\"제목\",\"summary\":null,\"content\":{}}",
            "{\"title\":null,\"summary\":null,\"content\":\"본문\"}",
            "{\"title\":\"　\",\"summary\":null,\"content\":\"본문\"}",
            "{\"title\":\"제목\",\"summary\":null,\"content\":\"　\"}",
            "{\"title\":\"제목\",\"summary\":null,\"content\":null}",
            "{\"title\":\"제목\",\"summary\":null,\"content\":\"## 핵심 요약\\n내용\",\"topic\":\"변경\"}",
            "{\"title\":\"제목\",\"title\":\"중복\",\"summary\":null,\"content\":\"## 핵심 요약\\n내용\"}",
            "{\"title\":\"제목\",\"summary\":null,\"content\":\"## 핵심 요약\\n내용\"} {}",
            "```json\n{\"title\":\"제목\",\"summary\":null,\"content\":\"본문\"}\n```"})
    @DisplayName("응답의 JSON 구조·실제 타입·필수값이 잘못되면 결과를 반환하지 않는다")
    void generate_failure_invalidJsonContract(String response) {
        // given
        when(client.complete(any())).thenReturn(response);

        // when & then
        assertThatThrownBy(
                () -> generator.generate(
                        DocumentGenerationInput.of(
                                "검색하자",
                                "검색"
                        )
                )
        ).hasMessage(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE.getMessage());
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("정상 JSON이어도 잘못된 Markdown은 거절한다")
    void generate_failure_invalidMarkdown() {
        // given
        when(client.complete(any()))
                .thenReturn("{\"title\":\"제목\",\"summary\":null,\"content\":\"## 핵심 요약\\n요약\\n## 할 일\\n- 없음\"}");

        // when & then
        assertThatThrownBy(
                () -> generator.generate(
                        DocumentGenerationInput.of(
                                "검색하자",
                                "검색"
                        )
                )
        ).hasMessage(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"결정|보류|미결정|할 일", "결정|보류|할 일|미결정", "결정|미결정|보류|할 일", "결정|미결정|할 일|보류", "결정|할 일|보류|미결정", "결정|할 일|미결정|보류",
            "보류|결정|미결정|할 일", "보류|결정|할 일|미결정", "보류|미결정|결정|할 일", "보류|미결정|할 일|결정", "보류|할 일|결정|미결정", "보류|할 일|미결정|결정",
            "미결정|결정|보류|할 일", "미결정|결정|할 일|보류", "미결정|보류|결정|할 일", "미결정|보류|할 일|결정", "미결정|할 일|결정|보류", "미결정|할 일|보류|결정",
            "할 일|결정|보류|미결정", "할 일|결정|미결정|보류", "할 일|보류|결정|미결정", "할 일|보류|미결정|결정", "할 일|미결정|결정|보류", "할 일|미결정|보류|결정"})
    @DisplayName("고정 섹션의 모든 순열을 한 번의 LLM 호출로 표시 순서에 맞춰 반환한다")
    void generate_fixedSectionPermutations_success(String order) {
        // given
        StringBuilder content = new StringBuilder("## 핵심 요약\n요약\n\n");
        for (String name : order.split("\\|")) {
            content.append("## ");
            content.append(name);
            content.append("\n");
            content.append(name);
            content.append(" 본문\n\n");
        }
        stubContent(content.toString());

        // when
        DocumentGenerationResult result = generateDocument();

        // then
        assertThat(result.content()).isEqualTo("""
                ## 핵심 요약
                요약

                ## 결정
                결정 본문

                ## 보류
                보류 본문

                ## 미결정
                미결정 본문

                ## 할 일
                할 일 본문

                """);
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("추가 섹션 사이의 고정 섹션만 앞으로 옮기고 원본 본문과 코드 블록을 보존한다")
    void generate_interleavedSections_success() {
        // given
        String summary = "## 핵심 요약\r\n요약  \r\n\r\n";
        String background = "## 배경\r\n배경 본문\r\n```markdown\r\n## 결정\r\n코드 안의 제목\r\n```\r\n\r\n";
        String task = "## 할 일\r\n- 담당자: 수정안 작성  \r\n    들여쓰기 보존\r\n\r\n";
        String concern = "## 우려\r\n| 항목 | 내용 |\r\n| --- | --- |\r\n| 비용 | 증가 |\r\n\r\n";
        String deferred = "## 보류\r\n- 사용자 수가 1,000명을 넘으면 진행한다.\r\n\r\n";
        String proposal = "## 제안\r\n채택 여부를 논의하지 않은 제안";
        stubContent(summary + background + task + concern + deferred + proposal);

        // when
        DocumentGenerationResult result = generateDocument();

        // then
        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.summary()).isEqualTo("요약");
        assertThat(result.content()).isEqualTo(summary + deferred + task + background + concern + proposal);
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("끝 개행 없는 고정 섹션을 옮겨도 다음 제목과 붙지 않고 재정규화 결과가 같다")
    void generate_normalizedContent_success_idempotent() {
        // given
        stubContent("## 핵심 요약\n요약\n\n## 배경\n근거\n\n## 결정\n합의");

        // when
        DocumentGenerationResult first = generateDocument();
        stubContent(first.content());
        DocumentGenerationResult second = generateDocument();

        // then
        assertThat(first.content()).isEqualTo("## 핵심 요약\n요약\n\n## 결정\n합의\n\n## 배경\n근거\n\n");
        assertThat(second.content()).isEqualTo(first.content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"## 핵심 요약\n요약\n## 배경\n근거\n## 결정\n첫 합의\n## 결정\n중복 합의", "## 핵심 요약\n요약\n## 배경\n근거\n## 결정\n",
            "## 핵심 요약\n요약\n## 배경\n근거\n## 할 일\n- 없음", "## 핵심 요약\n요약\n## 배경\nhttps://example.com\n## 결정\n합의",
            "## 핵심 요약\n요약\n## 배경\n근거\n## 결정\n합의\n```text\n닫히지 않은 코드"})
    @DisplayName("섹션 순서를 정리해도 중복·빈 본문·placeholder·링크·미완성 코드는 거절한다")
    void generate_invalidReorderedContent_failure(String content) {
        // given
        stubContent(content);

        // when & then
        assertThatThrownBy(this::generateDocument)
                .hasMessage(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE.getMessage());
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }

    private void stubContent(String content) {
        ObjectNode response = mapper.createObjectNode();
        response.put(
                "title",
                "제목"
        );
        response.put(
                "summary",
                "요약"
        );
        response.put(
                "content",
                content
        );
        when(client.complete(any())).thenReturn(mapper.writeValueAsString(response));
    }

    private DocumentGenerationResult generateDocument() {
        DocumentGenerationInput input = DocumentGenerationInput.of(
                "저장된 전사 원문",
                "검색"
        );
        return generator.generate(input);
    }

    private String expectedSummary(String summary) {
        if ("요약".equals(summary)) {
            return summary;
        }
        return null;
    }

    @ParameterizedTest
    @EnumSource(value = LlmErrorCode.class, names = {"LLM_RATE_LIMITED", "LLM_TIMEOUT", "LLM_OUTPUT_LIMIT_EXCEEDED",
            "LLM_INPUT_LIMIT_EXCEEDED"})
    @DisplayName("공급자 오류는 원래 예외로 전달하며 숨은 재시도를 하지 않는다")
    void generate_failure_providerError(LlmErrorCode code) {
        // given
        LlmException failure = new LlmException(code);
        when(client.complete(any())).thenThrow(failure);

        // when & then
        assertThatThrownBy(
                () -> generator.generate(
                        DocumentGenerationInput.of(
                                "검색하자",
                                "검색"
                        )
                )
        ).isSameAs(failure);
        verify(client).complete(any());
        verifyNoMoreInteractions(client);
    }
}
