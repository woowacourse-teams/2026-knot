package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerGuidance;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult.Item;
import com.knot.backend.search.domain.SearchAttemptFailure;
import com.knot.backend.search.domain.SearchToolException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@Slf4j
public class SearchStreamingAnswerGenerator implements SearchAnswerGenerator {

    private final SearchLlmClient llm;
    private final SearchHistoryQuery history;
    private final SearchToolAccess access;
    private final SearchAgentProperties properties;
    private final ObjectMapper mapper;
    private final Optional<SearchSpringAiModel> springAi;
    private final Optional<SearchLlmClient> openAiFallback;

    public SearchStreamingAnswerGenerator(
            SearchLlmClient llm,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper
    ) {
        this(
                llm,
                history,
                access,
                properties,
                mapper,
                Optional.empty()
        );
    }

    public SearchStreamingAnswerGenerator(
            SearchLlmClient llm,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper,
            Optional<SearchSpringAiModel> springAi
    ) {
        this(
                llm,
                history,
                access,
                properties,
                mapper,
                springAi,
                Optional.empty()
        );
    }

    @Override
    public List<Long> generate(
            SearchToolScope scope,
            String question,
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    ) {
        return generateFromRetrieval(
                scope,
                question,
                new SearchRetrievalResult(
                        documents,
                        0
                ),
                onDelta
        );
    }

    @Override
    public List<Long> generateFromRetrieval(
            SearchToolScope scope,
            String question,
            SearchRetrievalResult retrieval,
            Consumer<String> onDelta
    ) {
        access.validate(scope);
        List<SearchDocumentResult> documents = retrieval.documents();
        boolean fullDocumentsOnly = !documents.isEmpty()
                && springAi.filter(model -> model.supports(scope.workspaceId()))
                        .isPresent();
        List<Map<String, Object>> previous = history.beforeQuestion(scope);
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                properties.getContextCharacterLimit(),
                question,
                scope.questionAcceptedAt(),
                fullDocumentsOnly,
                retrieval
        );
        try {
            budget.select(
                    documents,
                    previous
            );
        } catch (SearchToolException exception) {
            if (!documents.isEmpty() && "SEARCH_CONTEXT_LIMIT_EXCEEDED".equals(exception.getCode())) {
                access.validate(scope);
                onDelta.accept(SearchAnswerGuidance.DOCUMENT_BUDGET_EXHAUSTED);
                publishCoverage(
                        retrieval,
                        List.of(),
                        false,
                        onDelta
                );
                return List.of();
            }
            log.warn(
                    "Search context budget failed workspaceId={} answerId={} limitChars={} documents={} history={} failureCode={}",
                    scope.workspaceId(),
                    scope.answerId(),
                    properties.getContextCharacterLimit(),
                    documents.size(),
                    previous.size(),
                    exception.getMessage()
            );
            throw exception;
        }
        List<Map<String, Object>> messages = budget.messages();
        log.info(
                "Search context budget workspaceId={} answerId={} inputChars={} limitChars={} documents={} selectedDocuments={} history={} selectedHistory={} partial={} recoveredSection={}",
                scope.workspaceId(),
                scope.answerId(),
                mapper.writeValueAsString(messages)
                        .length(),
                properties.getContextCharacterLimit(),
                documents.size(),
                budget.documents()
                        .size(),
                previous.size(),
                budget.historyCount(),
                budget.partial(),
                budget.recoveredSection()
        );
        SearchAgentContext context = new SearchAgentContext(
                properties,
                mapper
        );
        context.validate(messages);
        ValidatedAnswer answer;
        try {
            answer = generateWithFallback(
                    scope,
                    messages,
                    budget.documents(),
                    fullDocumentsOnly
            );
        } catch (RuntimeException exception) {
            if (ServerErrorLogger.isControlFlow(exception) || SearchAttemptFailure.temporary(exception)
                    || SearchAttemptFailure.terminalNetwork(exception)) {
                throw exception;
            }
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Search answer phase=validation",
                    exception
            );
            access.validate(scope);
            List<SearchDocumentResult> related = budget.documents()
                    .stream()
                    .limit(3)
                    .toList();
            publishGuidance(
                    related,
                    onDelta
            );
            publishCoverage(
                    retrieval,
                    List.of(),
                    budget.documentsLimited(),
                    onDelta
            );
            log.info(
                    "Search answer outcome=guidance workspaceId={} answerId={}",
                    scope.workspaceId(),
                    scope.answerId()
            );
            return related.stream()
                    .map(SearchDocumentResult::documentId)
                    .toList();
        }
        onDelta.accept(answer.text());
        publishCoverage(
                retrieval,
                answer.ids(),
                budget.documentsLimited(),
                onDelta
        );
        log.info(
                "Search answer outcome=generated workspaceId={} answerId={}",
                scope.workspaceId(),
                scope.answerId()
        );
        return answer.ids();
    }

    private ValidatedAnswer generateWithFallback(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            List<SearchDocumentResult> documents,
            boolean fullDocumentsOnly
    ) {
        try {
            return validateAttempt(
                    scope,
                    documents,
                    delta -> stream(
                            scope,
                            messages,
                            fullDocumentsOnly,
                            delta
                    )
            );
        } catch (RuntimeException primary) {
            if (openAiFallback.isEmpty() || !SearchAttemptFailure.temporary(primary)) {
                throw primary;
            }
            access.validate(scope);
            log.info(
                    "Search fallback provider=openai answerId={} failureCode={}",
                    scope.answerId(),
                    ServerErrorLogger.safeFailureCode(primary)
            );
            try {
                return validateAttempt(
                        scope,
                        documents,
                        delta -> openAiFallback.get()
                                .stream(
                                        scope,
                                        messages,
                                        delta
                                )
                );
            } catch (RuntimeException fallback) {
                if (ServerErrorLogger.isControlFlow(fallback)) {
                    throw fallback;
                }
                throw SearchAttemptFailure.exhaustedFallback(fallback);
            }
        }
    }

    private ValidatedAnswer validateAttempt(
            SearchToolScope scope,
            List<SearchDocumentResult> documents,
            Consumer<Consumer<String>> stream
    ) {
        StringBuilder text = new StringBuilder();
        SearchAnswerJsonStream answer = new SearchAnswerJsonStream(
                mapper,
                text::append
        );
        stream.accept(content -> {
            access.validate(scope);
            answer.accept(content);
        });
        access.validate(scope);
        List<Long> ids = answer.complete(documents);
        return new ValidatedAnswer(
                text.toString(),
                ids
        );
    }

    private record ValidatedAnswer(
            String text,
            List<Long> ids
    ) {
    }

    private void publishCoverage(
            SearchRetrievalResult retrieval,
            List<Long> citedIds,
            boolean documentsLimited,
            Consumer<String> onDelta
    ) {
        StringBuilder guidance = new StringBuilder();
        for (Item item : retrieval.items()) {
            if (item.documentIds()
                    .stream()
                    .anyMatch(citedIds::contains)) {
                continue;
            }
            String description = switch (item.status()) {
                case UNAVAILABLE -> "회의 기록을 조회하지 못했어요.";
                case NO_MATCH -> "검색한 범위에서 관련 기록을 찾지 못했어요.";
                case MATCHED, EXCLUDED -> "답변을 뒷받침할 근거를 확인하지 못했어요.";
            };
            guidance.append("\n\n요청의 ")
                    .append(item.ordinal())
                    .append("번째 항목은 ")
                    .append(description);
        }
        if (retrieval.candidateLimited() && !documentsLimited) {
            guidance.append("\n\n")
                    .append(SearchAnswerGuidance.CANDIDATE_LIMITED);
        }
        if (documentsLimited) {
            guidance.append("\n\n")
                    .append(SearchAnswerGuidance.DOCUMENT_BUDGET_LIMITED);
        }
        if (!guidance.isEmpty()) {
            onDelta.accept(guidance.toString());
        }
    }

    private void stream(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            boolean fullDocumentsOnly,
            Consumer<String> onDelta
    ) {
        if (fullDocumentsOnly) {
            springAi.orElseThrow()
                    .stream(
                            scope,
                            messages,
                            onDelta
                    );
            return;
        }
        llm.stream(
                scope,
                messages,
                onDelta
        );
    }

    private void publishGuidance(
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    ) {
        if (documents.isEmpty()) {
            onDelta.accept(SearchAnswerGuidance.UNAVAILABLE);
            return;
        }
        // This is a document-list response: each persisted source is explicitly used by
        // name.
        String titles = documents.stream()
                .map(document -> "- " + document.title())
                .collect(java.util.stream.Collectors.joining("\n"));
        onDelta.accept(SearchAnswerGuidance.RELATED_DOCUMENTS + "\n\n" + titles);
    }
}
