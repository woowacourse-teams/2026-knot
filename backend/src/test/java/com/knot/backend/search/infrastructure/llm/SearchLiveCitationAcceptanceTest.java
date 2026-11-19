package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("acceptance")
@EnabledIfEnvironmentVariable(named = "KNOT_LIVE_CITATION", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"knot.llm.enabled=true",
        "knot.llm.provider=lm-studio", "knot.llm.request-timeout=PT25S", "knot.search.agent.enabled=true",
        "knot.search.agent.signing-key=test-signing-key-32-characters-minimum", "knot.search.worker.enabled=true",
        "knot.search.worker.poll-interval=PT1H", "knot.search.worker.recovery-interval=PT1H",
        "knot.search.meeting-pipeline.enabled=true", "knot.search.answer.spring-ai.enabled=true",
        "knot.search.answer.spring-ai.workspace-ids=1"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Timeout(300)
class SearchLiveCitationAcceptanceTest {
    private static final int REPETITIONS = 3;

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JwtProperties jwt;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private LlmProperties llm;
    @Autowired
    private SearchAgentProperties search;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SearchToolAccess access;
    @Autowired
    private Clock clock;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoSpyBean
    private SearchSpringAiModel observedModel;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<ProviderAttempt> attempts = new ArrayList<>();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add(
                "knot.llm.base-url",
                () -> requiredEnvironment("KNOT_LIVE_CITATION_BASE_URL")
        );
        registry.add(
                "knot.llm.model",
                () -> requiredEnvironment("KNOT_LIVE_CITATION_MODEL")
        );
        registry.add(
                "knot.llm.api-token",
                () -> requiredEnvironment("KNOT_LIVE_CITATION_API_TOKEN")
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @Test
    @DisplayName("합성 회의 문서로 실제 모델의 응답과 사용자가 받은 본문·근거를 함께 기록한다")
    void evaluate_success_recordsRealModelThroughSse() throws Exception {
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("인용 평가 사용자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        assertThat(workspace).isEqualTo(1);
        Session session = authenticate(
                workspace,
                member
        );
        observeProvider(workspace);
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchUsePlan.of(SearchIntent.MEETING_SEARCH));

        SearchDocumentResult alerts = document(
                session,
                "모니터링 알림 회의",
                "모니터링 알림 도입은 검토 중이며 확정된 정책은 없다. 고객별 적용 범위는 미정이다. " + "도입이 승인되는 경우에만 야간에는 긴급 장애에 한해 알림을 보내는 안을 검토한다."
        );
        SearchDocumentResult deployment = document(
                session,
                "배포 운영 회의",
                "배포 운영 회의에서는 배포 30분 뒤 확인하기로 결정했다. " + "첫 배포 점검은 지수가 담당한다. 점검 결과가 정상이면 트래픽을 확대하고 오류가 있으면 중단한다."
        );
        SearchDocumentResult rehearsal = document(
                session,
                "배포 리허설 회의",
                "배포 리허설에서는 배포 1시간 뒤 점검하는 안을 검토했다. 이 기록에는 운영 회의 결정을 바꾼 내용이 없다."
        );
        SearchDocumentResult cache = document(
                session,
                "검색 캐시 회의",
                "검색 캐시는 현재 메모리 방식으로 운용한다. 회의에서 캐시 만료 시간을 조사하기로 했다."
        );

        for (int repeat = 1; repeat <= REPETITIONS; repeat++) {
            Turn first = observeTurn(
                    session,
                    repeat,
                    "alerts-initial",
                    null,
                    "모니터링 알림 도입을 확정했어?",
                    List.of(alerts)
            );
            observeTurn(
                    session,
                    repeat,
                    "alerts-followup",
                    first.conversationId(),
                    "그럼 고객별 적용 범위와 야간 알림 조건은?",
                    List.of(alerts)
            );
            observeTurn(
                    session,
                    repeat,
                    "meeting-attribution",
                    null,
                    "배포 운영 회의에서 점검 시간과 담당자, 확대 조건을 어떻게 정했어?",
                    List.of(
                            rehearsal,
                            deployment
                    )
            );
            observeTurn(
                    session,
                    repeat,
                    "decision-history",
                    null,
                    "배포 점검 시간이 1시간으로 바뀐 거야? 두 회의에서 각각 어떻게 다뤘어?",
                    List.of(
                            deployment,
                            rehearsal
                    )
            );
            observeTurn(
                    session,
                    repeat,
                    "not-found",
                    null,
                    "검색 캐시 회의에서 Redis 도입을 확정했지? 도입 근거를 알려줘.",
                    List.of(cache)
            );
        }
    }

    private SearchDocumentResult document(
            Session session,
            String title,
            String content
    ) {
        long id = new SearchMessageFixtures(jdbc).saveDocument(
                session.workspaceId(),
                session.memberId()
        );
        jdbc.sql("UPDATE documents SET title = :title, content = :content WHERE id = :id")
                .param(
                        "title",
                        title
                )
                .param(
                        "content",
                        content
                )
                .param(
                        "id",
                        id
                )
                .update();
        return new SearchDocumentResult(
                id,
                title,
                "합성 회의",
                content
        );
    }

    private void observeProvider(long workspace) {
        SearchSpringAiModel actual = new SearchSpringAiModel(
                llm,
                search,
                json,
                access,
                clock,
                List.of(workspace)
        );
        doAnswer(call -> {
            List<Map<String, Object>> input = call.getArgument(1);
            Consumer<String> downstream = call.getArgument(2);
            StringBuilder response = new StringBuilder();
            try {
                actual.stream(
                        call.getArgument(0),
                        input,
                        chunk -> {
                            response.append(chunk);
                            downstream.accept(chunk);
                        }
                );
            } finally {
                JsonNode user = json.readTree(
                        input.getLast()
                                .get("content")
                                .toString()
                );
                attempts.add(
                        new ProviderAttempt(
                                user.path("evidence"),
                                response.toString()
                        )
                );
            }
            return null;
        }).when(observedModel)
                .stream(
                        any(),
                        any(),
                        any()
                );
    }

    private Turn observeTurn(
            Session session,
            int repeat,
            String caseId,
            Long conversation,
            String question,
            List<SearchDocumentResult> documents
    ) throws Exception {
        attempts.clear();
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        documents,
                        0
                )
        );
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put(
                "case",
                caseId
        );
        observation.put(
                "repeat",
                repeat
        );
        String endpoint = session.path();
        if (conversation != null) {
            endpoint += "/" + conversation + "/questions";
        }
        HttpRequest request = HttpRequest.newBuilder(uri(endpoint))
                .timeout(Duration.ofSeconds(50))
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "Accept",
                        "text/event-stream"
                )
                .header(
                        "Cookie",
                        session.cookies()
                )
                .header(
                        "X-XSRF-TOKEN",
                        session.csrf()
                )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(
                                        Map.of(
                                                "content",
                                                question,
                                                "requestId",
                                                UUID.randomUUID()
                                        )
                                )
                        )
                )
                .build();
        long started = System.nanoTime();
        HttpResponse<InputStream> stream = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(stream.statusCode()).isEqualTo(200);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream.body(),
                        StandardCharsets.UTF_8
                )
        ); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Event receipt = readEvent(reader);
            assertThat(receipt.name()).isEqualTo("accepted");
            long answer = receipt.data()
                    .path("answerMessageId")
                    .asLong();
            long conversationId = receipt.data()
                    .path("conversationId")
                    .asLong();
            assertThat(answer).isPositive();
            assertThat(conversationId).isPositive();
            var work = executor.submit(() -> worker.execute(answer));
            StringBuilder body = new StringBuilder();
            List<Long> sseCitedIds = new ArrayList<>();
            String terminal = "";
            Long firstDeltaMs = null;
            for (Event event = readEvent(reader); event != null; event = readEvent(reader)) {
                if ("delta".equals(event.name())) {
                    String text = event.data()
                            .path("text")
                            .asString("");
                    if (!text.isBlank() && firstDeltaMs == null) {
                        firstDeltaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    }
                    body.append(text);
                }
                if ("evidence".equals(event.name())) {
                    for (JsonNode item : event.data()
                            .path("items")) {
                        sseCitedIds.add(
                                item.path("documentId")
                                        .asLong()
                        );
                    }
                }
                if (List.of(
                        "completed",
                        "failed",
                        "stopped"
                )
                        .contains(event.name())) {
                    terminal = event.name();
                    break;
                }
            }
            work.get(
                    50,
                    TimeUnit.SECONDS
            );
            List<Long> citedIds = jdbc
                    .sql("SELECT document_id FROM search_evidences WHERE message_id = :id ORDER BY rank")
                    .param(
                            "id",
                            answer
                    )
                    .query(Long.class)
                    .list();
            observation.put(
                    "firstDeltaMs",
                    firstDeltaMs
            );
            observation.put(
                    "terminal",
                    terminal
            );
            observation.put(
                    "body",
                    body.toString()
            );
            observation.put(
                    "citedIds",
                    citedIds
            );
            observation.put(
                    "providerAttempts",
                    List.copyOf(attempts)
            );
            System.out.println("LIVE_CITATION " + json.writeValueAsString(observation));

            assertThat(attempts).as("실제 Spring AI 모델 호출")
                    .isNotEmpty();
            assertThat(terminal).isEqualTo("completed");
            assertThat(firstDeltaMs).isNotNull();
            assertThat(sseCitedIds).containsExactlyElementsOf(citedIds);
            assertThat(citedIds).isSubsetOf(
                    documents.stream()
                            .map(SearchDocumentResult::documentId)
                            .toList()
            );
            assertThat(body.toString()).isEqualTo(
                    jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                            .param(
                                    "id",
                                    answer
                            )
                            .query(String.class)
                            .single()
            );
            return new Turn(conversationId);
        }
    }

    private Session authenticate(
            long workspace,
            long member
    ) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        String csrf = json.readTree(response.body())
                .path("token")
                .asString();
        String csrfCookie = response.headers()
                .allValues("Set-Cookie")
                .stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow()
                .split(
                        ";",
                        2
                )[0];
        String accessCookie = jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        member,
                        "인용 평가 사용자",
                        null
                )
        );
        return new Session(
                workspace,
                member,
                csrf,
                csrfCookie + "; " + accessCookie
        );
    }

    private Event readEvent(BufferedReader reader) throws Exception {
        String name = null;
        String data = null;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.isEmpty() && name != null) {
                return new Event(
                        name,
                        json.readTree(data)
                );
            }
            if (line.startsWith("event:")) {
                name = line.substring("event:".length())
                        .strip();
            }
            if (line.startsWith("data:")) {
                data = line.substring("data:".length())
                        .strip();
            }
        }
        return null;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Event(
            String name,
            JsonNode data
    ) {
    }

    private record Turn(long conversationId) {
    }

    private record ProviderAttempt(
            JsonNode inputEvidence,
            String rawOutput
    ) {
    }

    private record Session(
            long workspaceId,
            long memberId,
            String csrf,
            String cookies
    ) {

        String path() {
            return "/api/v1/workspaces/" + workspaceId + "/search/conversations";
        }
    }
}
