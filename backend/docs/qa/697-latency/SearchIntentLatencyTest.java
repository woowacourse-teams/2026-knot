package com.knot.backend.search.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchGenerationAvailability;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("manual-live-model")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, DirectSearchLatencyConfiguration.class})
@TestApplicationProperties
@Timeout(1200)
class SearchIntentLatencyTest {
    private static final String SINGLE_MEETING = "single_meeting";
    private static final String CROSS_MEETING = "cross_meeting";
    private static final int WARMUP_SAMPLES = 3;

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JwtProperties jwt;
    @Autowired
    private JsonMapper json;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private SearchAgentProperties agent;
    @MockitoBean
    private SearchGenerationAvailability availability;

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "knot.search.agent.enabled",
                () -> "true"
        );
        registry.add(
                "knot.search.worker.enabled",
                () -> "false"
        );
        registry.add(
                "knot.search.agent.signing-key",
                () -> "manual-latency-fixture-signing-key-0123456789"
        );
        registry.add(
                "knot.llm.enabled",
                () -> "true"
        );
        registry.add(
                "knot.llm.base-url",
                SearchIntentLatencyTest::llmBaseUrl
        );
        registry.add(
                "knot.llm.api-token",
                () -> System.getenv("KNOT_LLM_API_KEY")
        );
        registry.add(
                "knot.llm.model",
                () -> "qwen3.8-27b"
        );
    }

    private static String llmBaseUrl() {
        String configured = System.getenv("KNOT_LLM_BASE_URL");
        if (configured == null || configured.isBlank()) {
            return "http://127.0.0.1:18997";
        }
        return configured;
    }

    @Test
    void joinsFactAcrossDeltaBoundaries() {
        String first = deltaText("event:delta\ndata:{\"text\":\"담당자는 흑\"}\n");
        String second = deltaText("event:delta\ndata:{\"text\":\"곰입니다.\"}\n");
        assertThat(first + second).isEqualTo("담당자는 흑곰입니다.");
    }

    @Test
    void compareFirstClientDelta() throws Exception {
        agent.setMcpBaseUrl(URI.create("http://127.0.0.1:" + port));
        String scenario = scenario();
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("측정 사용자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        List<Long> expectedDocumentIds = saveFixtures(
                workspace,
                member,
                scenario
        );
        String access = jwt.getCookieName() + "=" + tokens.issue(
                AuthenticatedMember.of(
                        member,
                        "측정 사용자",
                        null
                )
        );
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> csrf = client.send(
                    HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(csrf.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(csrf.body());
            JsonNode tokenNode = body.path("token");
            String token = tokenNode.asString();
            String cookie = csrf.headers()
                    .allValues("Set-Cookie")
                    .stream()
                    .filter(value -> value.startsWith("XSRF-TOKEN="))
                    .findFirst()
                    .orElseThrow()
                    .split(";")[0];
            int samples = Integer.parseInt(
                    System.getProperty(
                            "latency.samples",
                            "30"
                    )
            );
            assertThat(samples).isPositive();
            List<Long> measuredFirstDeltas = new ArrayList<>();
            List<Long> measuredTotals = new ArrayList<>();
            int totalRuns = WARMUP_SAMPLES + samples;
            for (int index = 0; index < totalRuns; index++) {
                long[] result = measure(
                        client,
                        workspace,
                        access + "; " + cookie,
                        token,
                        scenario,
                        expectedDocumentIds,
                        index,
                        index < WARMUP_SAMPLES
                );
                if (index < WARMUP_SAMPLES) {
                    continue;
                }
                measuredFirstDeltas.add(result[1]);
                measuredTotals.add(result[2]);
            }
            printSummary(
                    scenario,
                    measuredFirstDeltas,
                    measuredTotals
            );
        }
    }

    private long[] measure(
            HttpClient client,
            long workspace,
            String cookies,
            String csrf,
            String scenario,
            List<Long> expectedDocumentIds,
            int index,
            boolean warmup
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/workspaces/" + workspace + "/search/conversations"))
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
                        cookies
                )
                .header(
                        "X-XSRF-TOKEN",
                        csrf
                )
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(
                                        Map.of(
                                                "content",
                                                question(scenario),
                                                "requestId",
                                                UUID.randomUUID()
                                                        .toString()
                                        )
                                )
                        )
                )
                .build();
        long start = System.nanoTime();
        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        long acceptedMs = -1;
        long deltaMs = -1;
        String terminal = "EOF";
        StringBuilder text = new StringBuilder();
        long answerId;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        response.body(),
                        StandardCharsets.UTF_8
                )
        ); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String event = readEvent(reader);
            assertThat(event).contains("event:accepted");
            acceptedMs = elapsed(start);
            answerId = jdbc.sql("SELECT max(id) FROM search_messages WHERE role='ASSISTANT'")
                    .query(Long.class)
                    .single();
            Future<?> task = executor.submit(() -> worker.execute(answerId));
            for (String next = readEvent(reader); !next.isEmpty(); next = readEvent(reader)) {
                if (next.contains("event:delta") && deltaMs < 0) {
                    deltaMs = elapsed(start);
                }
                if (next.contains("event:delta")) {
                    text.append(deltaText(next));
                }
                if (next.contains("event:completed")) {
                    terminal = "COMPLETED";
                }
                if (next.contains("event:failed")) {
                    terminal = "FAILED";
                }
            }
            task.get(
                    150,
                    TimeUnit.SECONDS
            );
        }
        boolean expectedFact = expectedFact(scenario, text.toString());
        List<Long> evidenceDocumentIds = jdbc.sql(
                "SELECT document_id FROM search_evidences WHERE message_id=:answerId ORDER BY rank"
        )
                .param(
                        "answerId",
                        answerId
                )
                .query(Long.class)
                .list();
        long totalMs = elapsed(start);
        System.out.printf(
                "LATENCY_RESULT %s%n",
                json.writeValueAsString(
                        Map.ofEntries(
                                Map.entry("variant", variant()),
                                Map.entry("scenario", scenario),
                                Map.entry("sample", index),
                                Map.entry("warmup", warmup),
                                Map.entry("answerId", answerId),
                                Map.entry("acceptedMs", acceptedMs),
                                Map.entry("firstDeltaMs", deltaMs),
                                Map.entry("totalMs", totalMs),
                                Map.entry("terminal", terminal),
                                Map.entry("expectedFact", expectedFact),
                                Map.entry("evidenceDocumentIds", evidenceDocumentIds)
                        )
                )
        );
        assertThat(terminal).isEqualTo("COMPLETED");
        assertThat(deltaMs).isPositive();
        assertThat(expectedFact).isTrue();
        assertThat(evidenceDocumentIds).containsAll(expectedDocumentIds);
        return new long[]{acceptedMs, deltaMs, totalMs};
    }

    private String variant() {
        return System.getProperty(
                "latency.variant",
                "agent"
        );
    }

    private String scenario() {
        String scenario = System.getProperty(
                "latency.scenario",
                SINGLE_MEETING
        );
        assertThat(scenario).isIn(
                SINGLE_MEETING,
                CROSS_MEETING
        );
        return scenario;
    }

    private List<Long> saveFixtures(
            long workspace,
            long member,
            String scenario
    ) {
        if (SINGLE_MEETING.equals(scenario)) {
            return List.of(saveDocument(
                    workspace,
                    member,
                    "배포 회의 결정",
                    "배포",
                    "# 배포 회의\n배포 담당자는 흑곰으로 결정했다. 배포 날짜는 금요일이다."
            ));
        }
        return List.of(
                saveDocument(
                        workspace,
                        member,
                        "6월 10일 결제 논의",
                        "결제",
                        "# 6월 10일 결제 회의\n초기 논의에서는 카드 결제를 사용하기로 했다."
                ),
                saveDocument(
                        workspace,
                        member,
                        "6월 12일 결제 결정",
                        "결제",
                        "# 6월 12일 결제 회의\n카드 결제에서 계좌 이체로 변경하기로 결정했다. 변경 이유는 결제 수수료를 줄이고 정산을 단순화하기 위해서다."
                )
        );
    }

    private long saveDocument(
            long workspace,
            long member,
            String title,
            String topic,
            String content
    ) {
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET title=:title, topic=:topic, content=:content WHERE id=:id")
                .param("title", title)
                .param("topic", topic)
                .param("content", content)
                .param("id", document)
                .update();
        return document;
    }

    private String question(String scenario) {
        if (SINGLE_MEETING.equals(scenario)) {
            return "지난 배포 회의에서 배포 담당자는 누구로 정했어?";
        }
        return "6월 10일과 6월 12일 회의에서 결제 수단 결정이 어떻게 바뀌었고, 왜 바뀌었어?";
    }

    private boolean expectedFact(
            String scenario,
            String answer
    ) {
        if (SINGLE_MEETING.equals(scenario)) {
            return answer.contains("흑곰");
        }
        return answer.contains("카드") && answer.contains("계좌") && answer.contains("수수료");
    }

    private void printSummary(
            String scenario,
            List<Long> firstDeltas,
            List<Long> totals
    ) throws Exception {
        System.out.printf(
                "LATENCY_SUMMARY %s%n",
                json.writeValueAsString(
                        Map.of(
                                "variant",
                                System.getProperty("latency.variant"),
                                "scenario",
                                scenario,
                                "samples",
                                firstDeltas.size(),
                                "firstDeltaP50Ms",
                                percentile(firstDeltas, 0.50),
                                "firstDeltaP95Ms",
                                percentile(firstDeltas, 0.95),
                                "totalP50Ms",
                                percentile(totals, 0.50),
                                "totalP95Ms",
                                percentile(totals, 0.95)
                        )
                )
        );
    }

    private long percentile(
            List<Long> values,
            double percentile
    ) {
        List<Long> sorted = values.stream()
                .sorted()
                .toList();
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(rank - 1);
    }

    private String readEvent(BufferedReader reader) throws Exception {
        StringBuilder event = new StringBuilder();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.isEmpty() && !event.isEmpty()) {
                return event.toString();
            }
            event.append(line)
                    .append('\n');
        }
        return "";
    }

    private String deltaText(String event) {
        String data = event.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring(5);
        JsonNode body = json.readTree(data);
        JsonNode text = body.path("text");
        return text.asString();
    }

    private long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

}
