package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchAnswerWorker;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.SearchQuestionIntakeService;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.testsupport.LlmHttpServer;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.provider=lm-studio", "knot.llm.api-token=fixture",
        "knot.llm.model=fixture-chat", "knot.search.agent.enabled=true",
        "knot.search.agent.followup-context-enabled=true",
        "knot.search.agent.signing-key=test-signing-key-32-characters-minimum", "knot.search.worker.enabled=true",
        "knot.search.worker.poll-interval=PT1H", "knot.search.worker.recovery-interval=PT1H",
        "knot.search.meeting-pipeline.enabled=true", "knot.search.index.enabled=true",
        "knot.search.index.query-enabled=true", "knot.search.index.workspace-ids=1",
        "knot.search.index.initial-delay=PT1H", "knot.search.index.embedding-model=fixture-e5",
        "knot.search.index.embedding-revision=fixture-v1", "knot.search.answer.spring-ai.enabled=true",
        "knot.search.answer.spring-ai.workspace-ids=1"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchHybridSpringAiIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final AtomicLong EVIDENCE = new AtomicLong();
    private static final LlmHttpServer UPSTREAM = upstream();
    private static final String ANSWER = "구체적인 적용 범위는 아직 미정입니다.";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SearchMeetingIndexSynchronizer synchronizer;
    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerWorker worker;
    @MockitoBean
    private SearchIntentClassifier classifier;

    @DynamicPropertySource
    static void upstreamSettings(DynamicPropertyRegistry registry) {
        registry.add(
                "knot.llm.base-url",
                () -> UPSTREAM.baseUrl()
                        .toString()
        );
        registry.add(
                "knot.search.index.embedding-url",
                () -> UPSTREAM.baseUrl()
                        .resolve("/v1/embeddings")
                        .toString()
        );
        String index = Path.of(
                "build",
                "hybrid-composition-" + UUID.randomUUID()
        )
                .toAbsolutePath()
                .toString();
        registry.add(
                "knot.search.index.directory",
                () -> index
        );
    }

    @AfterAll
    static void closeUpstream() {
        UPSTREAM.close();
    }

    @Test
    @DisplayName("Spring 워커가 Java 색인과 Spring AI를 함께 호출하고 검증한 답변과 근거를 저장한다")
    void execute_success_enabledFlagsComposeIndexGenerationAndStorage() {
        given(
                classifier.classify(
                        any(),
                        any()
                )
        ).willReturn(SearchIntent.MEETING_SEARCH);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        long member = fixtures.saveMember("통합 대조");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        assertThat(workspace).isEqualTo(1L);
        long document = messages.saveDocument(
                workspace,
                member
        );
        String decision = "구체적인 적용 범위는 아직 미정이다.";
        jdbc.sql("UPDATE documents SET title = '배포 회의', content = :content WHERE id = :id")
                .param(
                        "content",
                        "배포 상태를 확인했다.\n" + decision
                )
                .param(
                        "id",
                        document
                )
                .update();
        EVIDENCE.set(document);
        synchronizer.refresh();

        SearchQuestionAcceptedResult initial = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "문서 #" + document + "의 적용 범위가 확정됐어?",
                        UUID.randomUUID()
                )
        );
        long answerId = initial.answerMessageId();
        worker.execute(answerId);

        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(ANSWER);
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(Long.class)
                        .single()
        ).isEqualTo(document);
        assertThat(UPSTREAM.requestBody()).contains(
                "response_format",
                decision
        );
        assertThat(UPSTREAM.requestCount()).isEqualTo(3);

        long otherDocument = messages.saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET content = '다른 회의에서만 결정하는 조건' WHERE id = :id")
                .param(
                        "id",
                        otherDocument
                )
                .update();
        synchronizer.refresh();

        SearchQuestionAcceptedResult followup = intake.acceptNext(
                workspace,
                member,
                initial.conversationId(),
                new SearchQuestionCommand(
                        "왜?",
                        UUID.randomUUID()
                )
        );
        worker.execute(followup.answerMessageId());

        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                followup.answerMessageId()
                        )
                        .query(Long.class)
                        .single()
        ).isEqualTo(document);
        assertThat(UPSTREAM.requestBody()).contains(decision)
                .doesNotContain("다른 회의에서만 결정하는 조건");
        assertThat(
                jdbc.sql(
                        """
                                SELECT first.search_text = followup.search_text AND followup.recording_session_id = d.recording_session_id
                                FROM search_question_contexts first, search_question_contexts followup, documents d
                                WHERE first.question_message_id = :first AND followup.question_message_id = :followup AND d.id = :document
                                """
                )
                        .param(
                                "first",
                                initial.questionMessageId()
                        )
                        .param(
                                "followup",
                                followup.questionMessageId()
                        )
                        .param(
                                "document",
                                document
                        )
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        verify(classifier).classify(
                any(),
                any()
        );
        int requestsBeforePartial = UPSTREAM.requestCount();
        long partialAnswerId = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "1. 문서 #" + document + "의 결정\n2. 문서 #9999의 결정",
                        UUID.randomUUID()
                )
        )
                .answerMessageId();
        worker.execute(partialAnswerId);

        assertThat(
                jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                partialAnswerId
                        )
                        .query(String.class)
                        .single()
        ).contains(
                ANSWER,
                "요청의 2번째 항목은 검색한 범위에서 관련 기록을 찾지 못했어요."
        );
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                partialAnswerId
                        )
                        .query(Long.class)
                        .list()
        ).containsExactly(document);
        assertThat(UPSTREAM.requestBody()).contains(
                "searchItems",
                "E" + document
        )
                .doesNotContain("E9999");
        assertThat(UPSTREAM.requestCount()).isEqualTo(requestsBeforePartial + 2);

        long longDocument = messages.saveDocument(
                workspace,
                member
        );
        String condition = "이미지 링크는 제외하고 글만 이관한다. 구체적인 적용 범위는 아직 미정이다.";
        jdbc.sql("UPDATE documents SET title = '마이그레이션 회의', content = :content WHERE id = :id")
                .param(
                        "content",
                        "## 배경\n" + "관련 없는 회의 배경 ".repeat(6000) + "\n\n## 이관 결정 및 조건\n" + condition
                )
                .param(
                        "id",
                        longDocument
                )
                .update();
        EVIDENCE.set(longDocument);
        synchronizer.refresh();
        int requestsBeforeExcerpt = UPSTREAM.requestCount();
        long excerptAnswer = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "문서 #" + longDocument + "에서 마이그레이션 때 이관할 자료와 제외 조건은?",
                        UUID.randomUUID()
                )
        )
                .answerMessageId();
        worker.execute(excerptAnswer);

        assertThat(UPSTREAM.requestBody()).contains(
                condition,
                "본문 일부만 제공됨"
        )
                .doesNotContain("관련 없는 회의 배경");
        assertThat(UPSTREAM.requestCount()).isEqualTo(requestsBeforeExcerpt + 2);
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                excerptAnswer
                        )
                        .query(Long.class)
                        .list()
        ).containsExactly(longDocument);
    }

    private static LlmHttpServer upstream() {
        try {
            LlmHttpServer server = new LlmHttpServer();
            server.respond(
                    "/v1/embeddings",
                    exchange -> {
                        JsonNode texts = JSON.readTree(server.requestBody())
                                .path("input");
                        ArrayNode data = JSON.createArrayNode();
                        for (int index = 0; index < texts.size(); index++) {
                            float[] embedding = new float[SearchE5EmbeddingClient.DIMENSIONS];
                            embedding[0] = 1;
                            data.add(
                                    JSON.valueToTree(
                                            Map.of(
                                                    "index",
                                                    index,
                                                    "embedding",
                                                    embedding
                                            )
                                    )
                            );
                        }
                        send(
                                exchange,
                                "application/json",
                                JSON.writeValueAsString(
                                        Map.of(
                                                "data",
                                                data
                                        )
                                )
                        );
                    }
            );
            server.respond(
                    "/v1/chat/completions",
                    exchange -> {
                        Map<String, Object> answer = new LinkedHashMap<>();
                        answer.put(
                                "status",
                                "ANSWERED"
                        );
                        answer.put(
                                "decisionState",
                                "UNDECIDED"
                        );
                        answer.put(
                                "answer",
                                ANSWER
                        );
                        answer.put(
                                "evidenceIds",
                                List.of("E" + EVIDENCE.get())
                        );
                        answer.put(
                                "clarificationQuestion",
                                null
                        );
                        String fragment = JSON.writeValueAsString(answer);
                        Map<String, Object> choice = Map.of(
                                "index",
                                0,
                                "delta",
                                Map.of(
                                        "role",
                                        "assistant",
                                        "content",
                                        fragment
                                ),
                                "finish_reason",
                                "stop"
                        );
                        String event = JSON.writeValueAsString(
                                Map.of(
                                        "id",
                                        "fixture",
                                        "object",
                                        "chat.completion.chunk",
                                        "created",
                                        1,
                                        "model",
                                        "fixture-chat",
                                        "choices",
                                        List.of(choice)
                                )
                        );
                        send(
                                exchange,
                                "text/event-stream",
                                "data: " + event + "\n\ndata: [DONE]\n\n"
                        );
                    }
            );
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void send(
            HttpExchange exchange,
            String mime,
            String result
    ) throws IOException {
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .add(
                        "Content-Type",
                        mime
                );
        exchange.sendResponseHeaders(
                200,
                bytes.length
        );
        exchange.getResponseBody()
                .write(bytes);
    }
}
