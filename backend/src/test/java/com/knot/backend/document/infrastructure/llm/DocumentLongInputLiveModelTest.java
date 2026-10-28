package com.knot.backend.document.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.domain.model.DocumentGenerationInput;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DOCUMENT_LONG_INPUT_LIVE_TEST", matches = "true")
class DocumentLongInputLiveModelTest {
    @Test
    void generatesFromMultipleSyntheticChunksUsingRealModel() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(URI.create(System.getenv("LLM_BASE_URL")));
        properties.setApiToken(System.getenv("LLM_API_TOKEN"));
        properties.setModel(System.getenv("LLM_MODEL"));
        properties.setRequestTimeout(Duration.ofSeconds(150));
        ObjectMapper mapper = new ObjectMapper();
        LlmClient actual = new LmStudioClient(
                HttpClient.newHttpClient(),
                mapper,
                properties
        );
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger actualLimits = new AtomicInteger();
        boolean forced = Boolean.parseBoolean(
                System.getenv()
                        .getOrDefault(
                                "DOCUMENT_LIVE_FORCE_LIMIT",
                                "true"
                        )
        );
        LlmClient forceInitialLimit = request -> {
            if (calls.getAndIncrement() == 0 && forced) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
            try {
                return actual.complete(request);
            } catch (LlmException exception) {
                if (exception.getErrorCode() == LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                    actualLimits.incrementAndGet();
                }
                throw exception;
            }
        };
        LlmDocumentGenerator generator = new LlmDocumentGenerator(
                new DocumentGenerationPrompt(mapper),
                forceInitialLimit,
                mapper,
                new DocumentMarkdownValidator()
        );
        int repetitions = Integer.parseInt(
                System.getenv()
                        .getOrDefault(
                                "DOCUMENT_LIVE_REPETITIONS",
                                "80"
                        )
        );
        String transcript = "검색 기능은 사용자 수가 1000명을 넘으면 개발을 시작합니다. 그 전에는 시작하지 않습니다. ".repeat(repetitions)
                + "준호는 금요일까지 검색 기능의 요구사항을 작성하기로 했습니다.";
        long start = System.nanoTime();
        var result = generator.generate(
                DocumentGenerationInput.of(
                        transcript,
                        "검색 기능 도입"
                )
        );
        System.out.println(
                "LIVE_MODEL_DURATION_SECONDS=" + Duration.ofNanos(System.nanoTime() - start)
                        .toSeconds()
        );
        System.out.println("LIVE_MODEL_CALLS=" + calls.get());
        System.out.println("LIVE_MODEL_ACTUAL_INPUT_LIMITS=" + actualLimits.get());
        System.out.println("SYNTHETIC_DOCUMENT=" + result.content());
        if (forced) {
            assertThat(calls.get()).isGreaterThanOrEqualTo(4);
        }
        assertThat(result.content()).contains("검색");
        assertThat(result.content()).containsAnyOf(
                "1000",
                "1,000"
        );
        assertThat(result.content()).contains("금요일");
    }
}
