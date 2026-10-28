package com.knot.backend.document.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.document.domain.model.DocumentGenerationInput;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TopicEvidenceExtractorTest {
    private final LlmClient client = mock(LlmClient.class);
    private final TopicEvidenceExtractor extractor = new TopicEvidenceExtractor(
            client,
            new ObjectMapper()
    );

    @Test
    void splitsAgainIfAChunkExceedsProviderLimit() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED))
                .thenReturn(
                        "{\"evidence\":\"첫 근거\"}",
                        "{\"evidence\":\"다음 근거\"}"
                );
        String result = extractor.extract(
                DocumentGenerationInput.of(
                        "가".repeat(4000),
                        "검색"
                )
        );
        assertThat(result).contains(
                "첫 근거",
                "다음 근거"
        );
        verify(
                client,
                times(3)
        ).complete(any());
    }

    @Test
    void stopsWhenProviderCannotAcceptTheSmallestChunk() {
        when(client.complete(any())).thenThrow(new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED));
        assertThatThrownBy(
                () -> extractor.extract(
                        DocumentGenerationInput.of(
                                "가".repeat(256),
                                "검색"
                        )
                )
        ).isInstanceOf(LlmException.class)
                .hasMessage(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED.getMessage());
        verify(client).complete(any());
    }
}
