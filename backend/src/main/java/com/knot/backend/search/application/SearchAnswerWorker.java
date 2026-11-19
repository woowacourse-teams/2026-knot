package com.knot.backend.search.application;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.query.SearchQuestionContext;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.application.notion.SearchNotionAnswerFlow;
import com.knot.backend.search.application.notion.SearchNotionMaterial;
import com.knot.backend.search.application.notion.SearchNotionReading;
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
    private final SearchFollowupContextResolver followups;
    private final SearchNotionAnswerFlow notionAnswers;

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
                    failureCode(exception)
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

    private String failureCode(RuntimeException exception) {
        if (exception instanceof com.knot.backend.search.domain.SearchScopeExpiredException) {
            return "ANSWER_GENERATION_TIMEOUT";
        }
        if ("SEARCH_ACCESS_DENIED".equals(ServerErrorLogger.safeFailureCode(exception))) {
            return "SEARCH_ACCESS_DENIED";
        }
        return "ANSWER_GENERATION_FAILED";
    }

    private SearchRetrievalResult retrieve(
            SearchIntent intent,
            SearchToolScope scope,
            String question
    ) {
        if (!intent.requiresSearch()) {
            return SearchRetrievalResult.skipped();
        }
        return retrievers.getObject()
                .retrieve(
                        scope,
                        question
                );
    }

    private void generate(
            SearchAnswerWorkResult work,
            SearchToolScope scope,
            AtomicLong persistenceCount,
            AtomicLong persistenceNanos
    ) {
        boolean contextEnabled = agentProperties.isFollowupContextEnabled();
        boolean shortReference = contextEnabled && followups.isShortReference(work.question());
        Optional<SearchQuestionContext> previous = Optional.empty();
        if (shortReference) {
            previous = followups.previousEvidenceContext(scope);
        }
        long classificationStarted = System.nanoTime();
        SearchIntent intent;
        SearchUsePlan plan = SearchUsePlan.of(SearchIntent.MEETING_SEARCH);
        try {
            intent = SearchIntent.MEETING_SEARCH;
            if (previous.isEmpty()) {
                plan = classifiers.getObject()
                        .classify(
                                scope,
                                work.question()
                        );
                intent = plan.intent();
            }
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
        if (shortReference && intent.requiresSearch() && !plan.usesNotion()
                && previous.map(SearchQuestionContext::recordingSessionId)
                        .isEmpty()) {
            executions.completeGenerated(
                    work.answerId(),
                    work.token(),
                    SearchAnswerGuidance.CLARIFY_MEETING,
                    List.of(),
                    Set.of()
            );
            return;
        }
        if (contextEnabled && intent.requiresSearch()) {
            SearchQuestionContext context = previous.orElseGet(
                    () -> followups.describe(
                            work.question(),
                            work.questionAcceptedAt()
                    )
            );
            if (!executions.saveQuestionContext(
                    work.answerId(),
                    work.token(),
                    context
            )) {
                return;
            }
            if (context.recordingSessionId() != null) {
                scope = scope.forMeeting(context.recordingSessionId());
            }
        }
        SearchNotionReading notion = SearchNotionReading.none();
        if (plan.usesNotion()) {
            notion = notionAnswers.start(
                    work,
                    plan
            );
        }
        long retrievalStarted = System.nanoTime();
        SearchRetrievalResult retrieval;
        try {
            retrieval = retrieve(
                    intent,
                    scope,
                    work.question()
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
            retrieval = SearchRetrievalResult.unavailable();
        }
        // 노션 읽기가 모두 끝난 뒤에 작성 중(GENERATING)으로 바꾼다(SEARCH-R38).
        SearchNotionMaterial material = notion.await();
        if (!executions.recordRetrieval(
                work.answerId(),
                work.token(),
                retrieval.excludedCount(),
                retrieval.documents()
        )) {
            return;
        }
        if (material.planned()) {
            notionAnswers.answer(
                    work,
                    scope,
                    retrieval,
                    material,
                    retrievalGuidance(retrieval)
            );
            return;
        }
        List<SearchDocumentResult> documents = retrieval.documents();
        SearchAnswerGenerator generator = generators.getObject();
        List<Long> usedIds;
        StringBuilder content = new StringBuilder();
        long generationStarted = System.nanoTime();
        try {
            content.append(retrievalGuidance(retrieval));
            usedIds = generateAnswer(
                    generator,
                    scope,
                    work.question(),
                    retrieval,
                    content
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

    private String retrievalGuidance(SearchRetrievalResult retrieval) {
        if (retrieval.status() == SearchRetrievalResult.Status.UNAVAILABLE) {
            return SearchAnswerGuidance.RETRIEVAL_UNAVAILABLE;
        }
        if (retrieval.foundNothing()) {
            return SearchAnswerGuidance.NO_SEARCH_MATCHES;
        }
        if (retrieval.excludedCount() > 0 && retrieval.items()
                .isEmpty()) {
            return SearchAnswerGuidance.RETRIEVAL_LIMITED;
        }
        return "";
    }

    private List<Long> generateAnswer(
            SearchAnswerGenerator generator,
            SearchToolScope scope,
            String question,
            SearchRetrievalResult retrieval,
            StringBuilder content
    ) {
        if (retrieval.items()
                .isEmpty()) {
            return generator.generate(
                    scope,
                    question,
                    retrieval.documents(),
                    content::append
            );
        }
        return generator.generateFromRetrieval(
                scope,
                question,
                retrieval,
                content::append
        );
    }
}
