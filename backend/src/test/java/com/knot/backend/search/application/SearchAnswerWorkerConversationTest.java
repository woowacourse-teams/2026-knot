package com.knot.backend.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.domain.model.SearchIntent;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SearchAnswerWorkerConversationTest {
    private final SearchAnswerExecutionService executions = mock(SearchAnswerExecutionService.class);
    private final SearchDocumentRetriever retriever = mock(SearchDocumentRetriever.class);
    private final SearchAnswerGenerator generator = mock(SearchAnswerGenerator.class);
    private final SearchIntentClassifier classifier = mock(SearchIntentClassifier.class);
    private final UUID token = UUID.randomUUID();
    private SearchAnswerWorker worker;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(
                "retriever",
                retriever
        );
        beans.addBean(
                "generator",
                generator
        );
        beans.addBean(
                "classifier",
                classifier
        );
        worker = new SearchAnswerWorker(
                executions,
                beans.getBeanProvider(SearchDocumentRetriever.class),
                beans.getBeanProvider(SearchAnswerGenerator.class),
                Clock.systemUTC(),
                new SearchAgentProperties(),
                beans.getBeanProvider(SearchIntentClassifier.class)
        );
        when(executions.claim(5)).thenReturn(
                Optional.of(
                        new SearchAnswerWorkResult(
                                1,
                                2,
                                3,
                                4,
                                5,
                                token,
                                "질문",
                                Clock.systemUTC()
                                        .instant()
                        )
                )
        );
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(),
                        0
                )
        );
        when(
                executions.recordRetrieval(
                        eq(5L),
                        eq(token),
                        eq(0),
                        any()
                )
        ).thenReturn(true);
        when(
                executions.append(
                        eq(5L),
                        eq(token),
                        any()
                )
        ).thenReturn(true);
        when(
                generator.generate(
                        any(),
                        any(),
                        any(),
                        any()
                )
        ).thenAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("생성한 답변");
            return List.of();
        });
    }

    @ParameterizedTest
    @EnumSource(value = SearchIntent.class, names = "MEETING_SEARCH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("일반 질문은 고정 범위 거부 대신 문서 없이 대화 답변을 생성한다")
    void execute_success_generalConversation(SearchIntent intent) {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(intent);
        worker.execute(5);
        verifyNoInteractions(retriever);
        verifyGeneratedAnswer();
    }

    @Test
    @DisplayName("검색 문서가 0개여도 대화 답변 생성 경로를 실행한다")
    void execute_success_noDocumentsStillGeneratesAnswer() {
        worker.execute(5);
        verify(retriever).retrieve(
                any(),
                eq("질문")
        );
        verifyGeneratedAnswer();
    }

    @Test
    @DisplayName("질문 분류 오류는 기록 검색과 답변 생성으로 복구한다")
    void execute_success_classifierFallback() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenThrow(new SearchToolException("INVALID_INTENT_RESPONSE"));
        when(
                executions.isCurrent(
                        5,
                        token
                )
        ).thenReturn(true);
        worker.execute(5);
        verify(retriever).retrieve(
                any(),
                eq("질문")
        );
        verifyGeneratedAnswer();
    }

    @Test
    @DisplayName("검색 기술 오류에도 근거를 만들지 않고 대화 생성으로 이어간다")
    void execute_success_retrievalFallback() {
        when(
                retriever.retrieve(
                        any(),
                        any()
                )
        ).thenThrow(new SearchToolException("SEARCH_TRANSPORT_FAILED"));
        worker.execute(5);
        verify(generator).generate(
                any(),
                eq("질문"),
                eq(List.of()),
                any()
        );
        verify(executions).completeGenerated(
                5,
                token,
                SearchAnswerGuidance.RETRIEVAL_UNAVAILABLE + "생성한 답변",
                List.of(),
                Set.of()
        );
    }

    @Test
    @DisplayName("접근 제어 오류는 일반 대화로 우회하지 않는다")
    void execute_failure_accessDenied() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenThrow(new SearchToolException("SEARCH_ACCESS_DENIED"));
        worker.execute(5);
        verifyNoInteractions(
                retriever,
                generator
        );
        verify(executions).fail(
                5,
                token,
                "ANSWER_GENERATION_FAILED"
        );
    }

    @Test
    @DisplayName("이미 종료된 작업에는 답변을 생성하거나 저장하지 않는다")
    void execute_success_staleWorkIgnored() {
        when(
                executions.recordRetrieval(
                        eq(5L),
                        eq(token),
                        eq(0),
                        any()
                )
        ).thenReturn(false);
        worker.execute(5);
        verifyNoInteractions(generator);
        verify(
                executions,
                never()
        ).append(
                anyLong(),
                any(),
                any()
        );
        verify(
                executions,
                never()
        ).complete(
                anyLong(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("문서가 있으면 검색된 문서와 실제 사용한 ID를 완료 처리에 전달한다")
    void execute_success_documentEvidence() {
        List<SearchDocumentResult> documents = List.of(
                new SearchDocumentResult(
                        7,
                        "회의",
                        "결정",
                        "본문"
                )
        );
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
        doReturn(List.of(7L)).when(generator)
                .generate(
                        any(),
                        any(),
                        eq(documents),
                        any()
                );
        worker.execute(5);
        verify(executions).completeGenerated(
                5,
                token,
                "",
                List.of(7L),
                Set.of(7L)
        );
    }

    private void verifyGeneratedAnswer() {
        verify(generator).generate(
                any(),
                eq("질문"),
                eq(List.of()),
                any()
        );
        verify(executions).completeGenerated(
                5,
                token,
                "생성한 답변",
                List.of(),
                Set.of()
        );
        verify(
                executions,
                never()
        ).fail(
                anyLong(),
                any(),
                any()
        );
    }
}
