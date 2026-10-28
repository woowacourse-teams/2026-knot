package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DOCUMENT_LONG_WORKER_LIVE_TEST", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentGenerationLongLiveIntegrationTest {
    @Autowired
    private DocumentGenerationIntakeService intake;
    @Autowired
    private DocumentGenerationWorker worker;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    private DocumentTopicClassificationService classifier;
    @MockitoBean
    private LlmClient client;

    @Test
    void realModelKeepsJobAlivePast180SecondsAndPreservesDistantFacts() throws Exception {
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("합성 사용자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        long recording = fixtures.saveRecording(
                workspace,
                member,
                7200000
        );
        long transcript = fixtures.saveTranscript(recording);
        String input = transcript();
        jdbc.sql("UPDATE transcripts SET content = :content WHERE id = :id")
                .param(
                        "content",
                        input
                )
                .param(
                        "id",
                        transcript
                )
                .update();
        when(classifier.classify(anyString()))
                .thenReturn(DocumentTopicClassificationResult.fromNames(List.of("검색 기능 도입")));
        long classifyId = transactions.execute(
                status -> intake.acceptCompletedTranscript(
                        workspace,
                        recording,
                        transcript
                )
        )
                .classificationJobId();
        worker.execute(
                workspace,
                classifyId
        );
        long job = jdbc
                .sql("SELECT id FROM document_generation_jobs WHERE stage = 'GENERATION' AND transcript_id = :id")
                .param(
                        "id",
                        transcript
                )
                .query(Long.class)
                .single();

        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(URI.create(System.getenv("LLM_BASE_URL")));
        properties.setApiToken(System.getenv("LLM_API_TOKEN"));
        properties.setModel(System.getenv("LLM_MODEL"));
        properties.setRequestTimeout(Duration.ofSeconds(150));
        LlmClient actual = new LmStudioClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                properties
        );
        AtomicInteger calls = new AtomicInteger();
        when(client.complete(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new LlmException(LlmErrorCode.LLM_INPUT_LIMIT_EXCEEDED);
            }
            return actual.complete(invocation.getArgument(0));
        });

        long start = System.nanoTime();
        AtomicLong workerFinished = new AtomicLong();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            worker.execute(
                    workspace,
                    job
            );
            workerFinished.set(System.nanoTime());
        });
        Thread.sleep(180000);
        String at180 = status(job);
        System.out.println("AT_180_SECONDS_STATUS=" + at180);
        running.get(
                15,
                TimeUnit.MINUTES
        );
        long seconds = Duration.ofNanos(workerFinished.get() - start)
                .toSeconds();
        String finalStatus = status(job);
        System.out.println("INPUT_CHARACTERS=" + input.length());
        System.out.println("LIVE_CALLS_INCLUDING_FORCED=" + calls.get());
        System.out.println("TOTAL_SECONDS=" + seconds);
        System.out.println("FINAL_STATUS=" + finalStatus);
        assertThat(finalStatus).isEqualTo("SUCCEEDED");
        String result = jdbc.sql("SELECT content FROM documents WHERE document_generation_job_id = :id")
                .param(
                        "id",
                        job
                )
                .query(String.class)
                .single();
        System.out.println("SYNTHETIC_RESULT=" + result);
        assertThat(at180).isEqualTo("RUNNING");
        assertThat(seconds).isGreaterThanOrEqualTo(180);
        assertThat(result).containsAnyOf(
                "1200",
                "1,200"
        );
        assertThat(result).containsAnyOf(
                "1000",
                "1,000"
        );
        assertThat(result).contains("금요일");
        assertThat(result).contains("준호");
        assertThat(result).contains("## 보류");
        assertThat(result).doesNotContain(
                "## 결정",
                "800"
        );
    }

    private String status(long job) {
        return jdbc.sql("SELECT status FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        job
                )
                .query(String.class)
                .single();
    }

    private String transcript() {
        String[] discussion = {"배포 화면을 살펴봤습니다. 현재 메뉴에는 준비된 항목을 순서대로 표시합니다.",
                "알림 채널은 앱 내부와 이메일의 장단점을 별도로 검토해야 한다고 이야기했습니다.", "성능 점검은 기존에 수집된 자료를 기준으로 확인하고 추가 의견을 기록했습니다.",
                "문서가 많아질수록 찾기 어려울 수 있어서 팀은 정리와 탐색 방법을 논의했습니다.", "모바일 화면의 색상과 버튼 위치는 추후 디자인 회의에서 다시 보자는 의견이 나왔습니다."};
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 5600; index++) {
            if (index == 120) {
                text.append("민지: 검색 개발은 사용자가 1000명을 넘으면 시작하자는 제안입니다.\n");
            }
            if (index == 1600) {
                text.append("준호: 1000명보다 1200명이 낫겠습니다. 민지: 좋아요. 서연: 저도 동의해요. ")
                        .append("민지: 검색 개발은 사용자가 1200명을 넘으면 시작합니다. 그 전에는 개발하지 않습니다.\n");
            }
            if (index == 3200) {
                text.append("준호: 금요일까지 검색 요구사항을 작성하겠습니다. 서연: 확인했어요.\n");
            }
            text.append("참여자 ")
                    .append(index % 5 + 1)
                    .append(": ")
                    .append(discussion[index % discussion.length])
                    .append('\n');
        }
        return text.toString();
    }
}
