package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import com.knot.backend.recording.domain.model.RecordingTitle;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "RUN_RECORDING_TITLE_LIVE_TEST", matches = "true")
class RecordingTitleLiveModelTest {

    @Test
    @DisplayName("실모델이 회의 전사로 저장할 수 있는 짧은 녹음 제목을 만든다")
    void generate_success_realModel() {
        // given
        LlmRecordingTitleGenerator generator = generator();
        String transcript = """
                화자 1: 오늘은 회원 탈퇴 정책을 정해야 해요. 탈퇴하면 작성한 문서를 어떻게 할지가 문제예요.
                화자 2: 문서는 워크스페이스에 남기고 작성자 이름만 가리면 될 것 같아요.
                화자 1: 좋아요. 그럼 탈퇴 후 30일 동안은 다시 가입하면 복구되게 할까요?
                화자 2: 복구는 이번 버전에서는 빼고, 탈퇴하면 바로 처리하는 걸로 하죠.
                """;

        // when
        String title = RecordingTitle.normalize(generator.generate(transcript));

        // then
        assertThat(title).isNotBlank()
                .hasSizeLessThanOrEqualTo(RecordingTitle.MAX_LENGTH);
    }

    private LlmRecordingTitleGenerator generator() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create(System.getenv("LLM_BASE_URL")));
        properties.setApiToken(System.getenv("LLM_API_TOKEN"));
        properties.setModel(System.getenv("LLM_MODEL"));
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setRequestTimeout(Duration.ofSeconds(60));
        properties.validateEnabledConfiguration();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        ObjectMapper mapper = new ObjectMapper();
        return new LlmRecordingTitleGenerator(
                new LmStudioClient(
                        http,
                        mapper,
                        properties
                ),
                mapper
        );
    }
}
