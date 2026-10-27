package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "KNOT_LIVE_SUMMARY_TEST", matches = "true")
class MeetingSummaryLiveTest {
    @Test
    void summarizesLongSyntheticMeeting() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(URI.create(System.getenv("LLM_BASE_URL")));
        properties.setApiToken(System.getenv("LLM_API_TOKEN"));
        properties.setModel(System.getenv("LLM_MODEL"));
        properties.setRequestTimeout(Duration.ofSeconds(150));
        LmStudioClient client = new LmStudioClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                properties
        );
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger limits = new AtomicInteger();
        LlmClient countingClient = request -> {
            calls.incrementAndGet();
            try {
                return client.complete(request);
            } catch (LlmException exception) {
                if (exception.getErrorCode() == LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED) {
                    limits.incrementAndGet();
                }
                throw exception;
            }
        };
        LlmMeetingSummaryGenerator generator = new LlmMeetingSummaryGenerator(
                countingClient,
                new ObjectMapper()
        );
        String beginning = "첫 안건은 녹음 품질 개선이다. 녹음 품질은 먼저 측정하기로 합의했다.\n";
        String discussion = "참여자는 문서 검색의 필요성을 설명했다. 검색은 사용자가 천 명을 넘으면 개발하기로 했다.\n";
        String ending = "마지막 안건은 서버 배포이다. 배포 시점은 결정하지 않고 다음 회의에서 논의한다.\n";
        List<Map<String, String>> segments = new ArrayList<>();
        segments.add(
                Map.of(
                        "text",
                        beginning
                )
        );
        for (int index = 0; index < 2300; index++) {
            segments.add(
                    Map.of(
                            "text",
                            discussion
                    )
            );
        }
        segments.add(
                Map.of(
                        "text",
                        ending
                )
        );
        String input = new ObjectMapper().writeValueAsString(
                Map.of(
                        "transcription",
                        segments
                )
        );
        long start = System.nanoTime();
        String result = generator.generate(input);
        System.out.println(
                "live_summary_input_chars=" + input.length() + " elapsed_seconds="
                        + Duration.ofNanos(System.nanoTime() - start)
                                .toSeconds()
                        + " calls=" + calls.get() + " input_limit_responses=" + limits.get()
        );
        System.out.println("live_synthetic_summary=" + result);
        assertThat(result).startsWith("## 한눈에 보기")
                .contains("회의 핵심 결론:");
        assertThat(result).contains(
                "녹음",
                "검색",
                "배포"
        );
    }
}
