package com.knot.backend.search.application;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.domain.model.SearchIntent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.knot.backend.global.config.SearchAgentProperties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchAnswerWorker {
    private final SearchAnswerExecutionService executions;
    private final ObjectProvider<SearchDocumentRetriever> retrievers;
    private final ObjectProvider<SearchAnswerGenerator> generators;
    private final Clock clock;
    private final SearchAgentProperties agentProperties;
    private final ObjectProvider<SearchIntentClassifier> classifiers;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(long answerId) {
        Instant workerStartedAt = clock.instant();
        Optional<SearchAnswerWorkResult> claimed = executions.claim(answerId);
        if (claimed.isEmpty()) {
            return;
        }
        SearchAnswerWorkResult work = claimed.orElseThrow();
        long queueWaitMs = Duration.between(
                work.createdAt(),
                workerStartedAt
        )
                .toMillis();
        log.info(
                "Search latency phase=queue_wait workspaceId={} questionId={} answerId={} elapsedMs={} outcome=success",
                work.workspaceId(),
                work.questionId(),
                answerId,
                Math.max(
                        0,
                        queueWaitMs
                )
        );
        Instant startedAt = clock.instant();
        SearchToolScope scope = SearchToolScope.from(
                work,
                startedAt,
                startedAt.plus(agentProperties.getBudget())
        );
        AtomicLong persistenceCount = new AtomicLong();
        AtomicLong persistenceNanos = new AtomicLong();
        try {
            generate(
                    work,
                    scope,
                    persistenceCount,
                    persistenceNanos
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Search generation failed workspaceId=" + work.workspaceId() + " questionId=" + work.questionId()
                            + " answerId=" + answerId + " conversationId=" + work.conversationId()
                            + " phase=generation failureCode=" + ServerErrorLogger.safeFailureCode(exception),
                    exception
            );
            executions.fail(
                    answerId,
                    work.token(),
                    failureCode(
                            exception,
                            scope
                    )
            );
        } finally {
            log.info(
                    "Search latency phase=content_persistence workspaceId={} questionId={} answerId={} count={} elapsedMs={}",
                    work.workspaceId(),
                    work.questionId(),
                    answerId,
                    persistenceCount.get(),
                    persistenceNanos.get() / 1_000_000
            );
        }
    }

    private String failureCode(
            RuntimeException exception,
            SearchToolScope scope
    ) {
        if (!clock.instant()
                .isBefore(scope.expiresAt())) {
            return "ANSWER_GENERATION_TIMEOUT";
        }
        if ("SEARCH_ACCESS_DENIED".equals(ServerErrorLogger.safeFailureCode(exception))) {
            return "SEARCH_ACCESS_DENIED";
        }
        return "ANSWER_GENERATION_FAILED";
    }

    private void generate(
            SearchAnswerWorkResult work,
            SearchToolScope scope,
            AtomicLong persistenceCount,
            AtomicLong persistenceNanos
    ) {
        long classificationStarted = System.nanoTime();
        SearchIntent intent;
        try {
            intent = classifiers.getObject()
                    .classify(
                            scope,
                            work.question()
                    );
            log.info(
                    "Search latency phase=intent_classification workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} outcome=success intent={}",
                    work.workspaceId(),
                    work.questionId(),
                    work.answerId(),
                    (System.nanoTime() - classificationStarted) / 1_000_000,
                    work.question()
                            .length(),
                    intent
            );
        } catch (RuntimeException exception) {
            log.info(
                    "Search latency phase=intent_classification workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} outcome=failure failureCode={}",
                    work.workspaceId(),
                    work.questionId(),
                    work.answerId(),
                    (System.nanoTime() - classificationStarted) / 1_000_000,
                    work.question()
                            .length(),
                    ServerErrorLogger.safeFailureCode(exception)
            );
            if (ServerErrorLogger.isControlFlow(exception)) {
                throw exception;
            }
            if (!executions.isCurrent(
                    work.answerId(),
                    work.token()
            )) {
                return;
            }
            intent = SearchIntent.MEETING_SEARCH;
        }
        long retrievalStarted = System.nanoTime();
        SearchRetrievalResult retrieval;
        boolean retrievalUnavailable = false;
        try {
            retrieval = intent.requiresSearch()
                    ? retrievers.getObject()
                            .retrieve(
                                    scope,
                                    work.question()
                            )
                    : new SearchRetrievalResult(
                            List.of(),
                            0
                    );
            log.info(
                    "Search latency phase=document_retrieval workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} candidates={} excludedDocuments={} outcome=success",
                    work.workspaceId(),
                    work.questionId(),
                    work.answerId(),
                    (System.nanoTime() - retrievalStarted) / 1_000_000,
                    work.question()
                            .length(),
                    retrieval.documents()
                            .size(),
                    retrieval.excludedCount()
            );
        } catch (RuntimeException exception) {
            log.info(
                    "Search latency phase=document_retrieval workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} outcome=failure failureCode={}",
                    work.workspaceId(),
                    work.questionId(),
                    work.answerId(),
                    (System.nanoTime() - retrievalStarted) / 1_000_000,
                    work.question()
                            .length(),
                    ServerErrorLogger.safeFailureCode(exception)
            );
            if (ServerErrorLogger.isControlFlow(exception)) {
                throw exception;
            }
            retrievalUnavailable = true;
            retrieval = new SearchRetrievalResult(
                    List.of(),
                    0
            );
        }
        if (!executions.recordRetrieval(
                work.answerId(),
                work.token(),
                retrieval.excludedCount(),
                retrieval.documents()
        )) {
            return;
        }
        List<SearchDocumentResult> documents = retrieval.documents();
        SearchAnswerGenerator generator = generators.getObject();
        List<Long> usedIds;
        StringBuilder content = new StringBuilder();
        {
            long generationStarted = System.nanoTime();
            try {
                if (retrievalUnavailable) {
                    content.append(SearchAnswerGuidance.RETRIEVAL_UNAVAILABLE);
                }
                usedIds = generator.generate(
                        scope,
                        work.question(),
                        documents,
                        content::append
                );
                log.info(
                        "Search latency phase=answer_generation workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} documentCount={} outcome=success",
                        work.workspaceId(),
                        work.questionId(),
                        work.answerId(),
                        (System.nanoTime() - generationStarted) / 1_000_000,
                        work.question()
                                .length(),
                        documents.size()
                );
            } catch (RuntimeException exception) {
                log.info(
                        "Search latency phase=answer_generation workspaceId={} questionId={} answerId={} elapsedMs={} inputChars={} documentCount={} outcome=failure failureCode={}",
                        work.workspaceId(),
                        work.questionId(),
                        work.answerId(),
                        (System.nanoTime() - generationStarted) / 1_000_000,
                        work.question()
                                .length(),
                        documents.size(),
                        ServerErrorLogger.safeFailureCode(exception)
                );
                throw exception;
            }
        }
        Set<Long> candidates = documents.stream()
                .map(SearchDocumentResult::documentId)
                .collect(Collectors.toSet());
        long persistenceStarted = System.nanoTime();
        try {
            executions.completeGenerated(
                    work.answerId(),
                    work.token(),
                    content.toString(),
                    usedIds,
                    candidates
            );
        } finally {
            persistenceCount.incrementAndGet();
            persistenceNanos.addAndGet(System.nanoTime() - persistenceStarted);
        }
    }
}
