package com.knot.backend.search.application;

import com.knot.backend.search.domain.model.SearchIntent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import ch.qos.logback.classic.Level;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.testsupport.LogCapture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class SearchAnswerWorkerLoggingTest {
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final Instant WORKER_STARTED_AT = Instant.parse("2026-10-16T00:00:05Z");

    private final SearchAnswerExecutionService executions = mock(SearchAnswerExecutionService.class);
    private final SearchDocumentRetriever retriever = mock(SearchDocumentRetriever.class);
    private final SearchAnswerGenerator generator = mock(SearchAnswerGenerator.class);
    private final SearchIntentClassifier classifier = mock(SearchIntentClassifier.class);
    @org.mockito.Mock
    private ObjectProvider<SearchDocumentRetriever> retrievers;
    @org.mockito.Mock
    private ObjectProvider<SearchAnswerGenerator> generators;
    @org.mockito.Mock
    private ObjectProvider<SearchIntentClassifier> classifiers;

    @Test
    @DisplayName("작업 대기 시간은 answer ID와 기록하고 질문 본문은 로그에 남기지 않는다")
    void execute_success_logsQueueWaitWithoutContent() {
        SearchAnswerWorker worker = worker(
                Clock.fixed(
                        WORKER_STARTED_AT,
                        ZoneOffset.UTC
                )
        );
        when(executions.claim(5)).thenReturn(
                Optional.of(
                        new SearchAnswerWorkResult(
                                1,
                                2,
                                3,
                                4,
                                5,
                                TOKEN,
                                "private-question",
                                WORKER_STARTED_AT.minusMillis(1250)
                        )
                )
        );
        try (LogCapture logs = new LogCapture(SearchAnswerWorker.class)) {
            worker.execute(5);
            assertThat(
                    logs.events()
                            .stream()
                            .filter(
                                    event -> event.getFormattedMessage()
                                            .contains("phase=queue_wait")
                            )
                            .toList()
            ).singleElement()
                    .satisfies(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "questionId=4",
                                    "answerId=5",
                                    "elapsedMs=1250"
                            )
                                    .doesNotContain("private-question")
                    );
        }
    }

    @Test
    @DisplayName("탐색 생성과 실패 저장이 연달아 실패해도 최초 원인을 기록하고 저장 오류를 전파한다")
    void execute_failure_generationAndPersistence() {
        SearchAnswerWorker worker = worker();
        when(
                retriever.retrieve(
                        any(),
                        eq("private-question")
                )
        ).thenThrow(new IllegalStateException("private-provider"));
        when(
                executions.recordRetrieval(
                        eq(5L),
                        eq(TOKEN),
                        eq(0),
                        any()
                )
        ).thenReturn(true);
        when(generators.getObject()).thenReturn(generator);
        when(
                generator.generate(
                        any(),
                        any(),
                        any(),
                        any()
                )
        ).thenThrow(new IllegalStateException("private-provider"));
        IllegalStateException persistence = new IllegalStateException("private-sql");
        doThrow(persistence).when(executions)
                .fail(
                        5,
                        TOKEN,
                        "ANSWER_GENERATION_FAILED"
                );
        try (LogCapture logs = new LogCapture(SearchAnswerWorker.class)) {
            assertThatThrownBy(() -> worker.execute(5)).isSameAs(persistence);
            assertThat(
                    logs.events()
                            .stream()
                            .filter(event -> event.getLevel() != Level.INFO)
                            .toList()
            ).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains(
                                "workspaceId=1",
                                "questionId=4",
                                "answerId=5",
                                "phase=generation",
                                "failureCode=UNCLASSIFIED",
                                "IllegalStateException"
                        )
                                .doesNotContain(
                                        "private-question",
                                        "private-provider",
                                        "private-sql",
                                        TOKEN.toString()
                                );
                    });
            assertThat(logs.events()).filteredOn(
                    event -> event.getFormattedMessage()
                            .contains("phase=document_retrieval")
            )
                    .singleElement()
                    .satisfies(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "questionId=4",
                                    "answerId=5",
                                    "outcome=failure",
                                    "failureCode=UNCLASSIFIED"
                            )
                                    .doesNotContain(
                                            "private-question",
                                            "private-provider"
                                    )
                    );
        }
    }

    @Test
    @DisplayName("정상 취소는 DEBUG로 기록하고 기존 종료 상태 처리를 유지한다")
    void execute_success_cancelDebug() {
        SearchAnswerWorker worker = worker();
        when(
                retriever.retrieve(
                        any(),
                        eq("private-question")
                )
        ).thenThrow(new CancellationException("private"));
        try (LogCapture logs = new LogCapture(SearchAnswerWorker.class)) {
            worker.execute(5);
            assertThat(
                    logs.events()
                            .stream()
                            .filter(event -> event.getLevel() != Level.INFO)
                            .toList()
            ).singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        }
        verify(executions).fail(
                5,
                TOKEN,
                "ANSWER_GENERATION_FAILED"
        );
    }

    @Test
    @DisplayName("답변 생성 실패 시간과 단계는 기록하고 본문은 남기지 않는다")
    void execute_failure_logsGenerationPhaseWithoutContent() {
        SearchAnswerWorker worker = worker();
        SearchDocumentResult document = new SearchDocumentResult(
                7,
                "private-title",
                "private-topic",
                "private-document"
        );
        when(
                retriever.retrieve(
                        any(),
                        eq("private-question")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(document),
                        0
                )
        );
        when(
                executions.recordRetrieval(
                        5,
                        TOKEN,
                        0,
                        List.of(document)
                )
        ).thenReturn(true);
        when(generators.getObject()).thenReturn(generator);
        when(
                generator.generate(
                        any(),
                        eq("private-question"),
                        eq(List.of(document)),
                        any()
                )
        ).thenThrow(new IllegalStateException("private-provider"));
        try (LogCapture logs = new LogCapture(SearchAnswerWorker.class)) {
            worker.execute(5);
            assertThat(logs.events()).filteredOn(
                    event -> event.getFormattedMessage()
                            .contains("phase=answer_generation")
            )
                    .singleElement()
                    .satisfies(
                            event -> assertThat(event.getFormattedMessage()).contains(
                                    "answerId=5",
                                    "outcome=failure"
                            )
                                    .doesNotContain(
                                            "private-question",
                                            "private-document",
                                            "private-title",
                                            "private-provider"
                                    )
                    );
        }
    }

    private SearchAnswerWorker worker() {
        return worker(Clock.systemUTC());
    }

    private SearchAnswerWorker worker(Clock clock) {
        when(executions.claim(5)).thenReturn(
                Optional.of(
                        new SearchAnswerWorkResult(
                                1,
                                2,
                                3,
                                4,
                                5,
                                TOKEN,
                                "private-question",
                                clock.instant()
                        )
                )
        );
        when(retrievers.getObject()).thenReturn(retriever);
        when(classifiers.getObject()).thenReturn(classifier);
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        return new SearchAnswerWorker(
                executions,
                retrievers,
                generators,
                clock,
                new SearchAgentProperties(),
                classifiers
        );
    }
}
