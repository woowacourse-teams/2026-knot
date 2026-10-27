package com.knot.backend.document.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.document.domain.model.DocumentGenerationInput;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "TOPIC_CLASSIFICATION_LIVE_TEST", matches = "true")
class LongTranscriptTopicLiveTest {
    @Test
    void keepsDifferentProblemsSeparateWhenTheyShareTheSameWord() {
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
        LlmClient forcedLimit = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
            return actual.complete(request);
        };
        LlmDocumentTopicClassifier classifier = new LlmDocumentTopicClassifier(
                new DocumentTopicPrompt(mapper),
                forcedLimit,
                mapper
        );
        String transcript = """
                민지: 문서 확인 권한은 현재 워크스페이스 멤버에게 부여하겠습니다.
                서연: 탈퇴한 사용자의 문서 접근은 즉시 차단해야 합니다.
                준호: 확인 권한 규칙을 정리하겠습니다.
                """.repeat(40) + """
                서연: 문서 검색 속도가 느려져 색인 구조를 개선해야 합니다.
                민지: 색인을 데이터베이스에서 처리할지 별도 검색 엔진에서 처리할지는 아직 결정하지 않았습니다.
                """.repeat(45) + """
                준호: 녹음 파일 업로드가 끊길 때 이어 올리기 기능을 검토하겠습니다.
                민지: 업로드 실패 시 이미 올린 파일은 삭제하지 않습니다.
                """.repeat(45);
        long start = System.nanoTime();
        List<DocumentTopic> topics = classifier.classify(transcript);
        System.out.println("DISTINCT_INPUT_CHARACTERS=" + transcript.length());
        System.out.println("DISTINCT_CALLS_INCLUDING_FORCED=" + calls.get());
        System.out.println(
                "DISTINCT_DURATION_SECONDS=" + Duration.ofNanos(System.nanoTime() - start)
                        .toSeconds()
        );
        System.out.println(
                "DISTINCT_SYNTHETIC_TOPICS=" + topics.stream()
                        .map(DocumentTopic::value)
                        .toList()
        );
        assertThat(topics).anySatisfy(
                topic -> assertThat(topic.value()).containsAnyOf(
                        "권한",
                        "접근"
                )
        );
        assertThat(topics).anySatisfy(
                topic -> assertThat(topic.value()).containsAnyOf(
                        "검색",
                        "색인"
                )
        );
        assertThat(topics).anySatisfy(
                topic -> assertThat(topic.value()).containsAnyOf(
                        "업로드",
                        "녹음"
                )
        );
        assertThat(topics).hasSize(3);
        DocumentTopic upload = topics.stream()
                .filter(
                        topic -> topic.value()
                                .contains("업로드")
                )
                .findFirst()
                .orElseThrow();
        LlmDocumentGenerator generator = new LlmDocumentGenerator(
                new DocumentGenerationPrompt(mapper),
                actual,
                mapper,
                new DocumentMarkdownValidator()
        );
        String document = generator.generate(
                DocumentGenerationInput.from(
                        transcript,
                        upload
                )
        )
                .content();
        System.out.println("SYNTHETIC_UPLOAD_DOCUMENT=" + document);
        assertThat(document).containsAnyOf(
                "삭제하지",
                "유지",
                "보존"
        );
    }

    @Test
    void classifiesAndMergesTopicsAcrossRealModelCalls() {
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
        LlmClient forcedLimit = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
            return actual.complete(request);
        };
        LlmDocumentTopicClassifier classifier = new LlmDocumentTopicClassifier(
                new DocumentTopicPrompt(mapper),
                forcedLimit,
                mapper
        );
        String transcript = """
                민지: 문서 생성 실패 알림이 너무 어렵습니다. 쉬운 문구로 고칩시다.
                준호: '문서를 만들지 못했어요. 다시 시도해 주세요'로 수정안을 만들겠습니다.
                """.repeat(45) + """
                민지: 알림을 이메일로 보낼지 앱에서 보여줄지 결정해야 합니다.
                서연: 이메일은 앱을 열지 않아도 보지만 다른 메일에 묻힙니다.
                준호: 앱 내 알림은 작업 중 보기 편하지만 알림 화면 개발이 필요합니다.
                민지: 근거가 부족하니 오늘은 발송 채널을 정하지 맙시다.
                """.repeat(45) + """
                서연: 문서가 많아지면 검색 기능이 필요할 겁니다.
                민지: 사용자가 1200명을 넘으면 검색 기능 개발을 시작하고 그 전에는 개발하지 맙시다.
                준호: 동의합니다. 검색 요구사항은 금요일까지 정리하겠습니다.
                """.repeat(45) + """
                준호: 앞에서 이야기한 실패 알림 문구는 기술 용어를 제외해야 합니다.
                민지: 확인되지 않은 실패 원인은 넣지 않겠습니다.
                """.repeat(35);
        long start = System.nanoTime();
        List<DocumentTopic> topics = classifier.classify(transcript);
        System.out.println("INPUT_CHARACTERS=" + transcript.length());
        System.out.println("CALLS_INCLUDING_FORCED=" + calls.get());
        System.out.println(
                "DURATION_SECONDS=" + Duration.ofNanos(System.nanoTime() - start)
                        .toSeconds()
        );
        System.out.println(
                "SYNTHETIC_TOPICS=" + topics.stream()
                        .map(DocumentTopic::value)
                        .toList()
        );
        assertThat(topics).hasSizeBetween(
                3,
                5
        );
        assertThat(topics).anySatisfy(topic -> assertThat(topic.value()).contains("검색"));
        assertThat(topics).anySatisfy(topic -> assertThat(topic.value()).contains("알림"));
        assertThat(topics).anySatisfy(
                topic -> assertThat(topic.value()).containsAnyOf(
                        "채널",
                        "이메일",
                        "방식"
                )
        );
    }
}
