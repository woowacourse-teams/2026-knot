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

    @Override
    public List<Long> generate(
            SearchToolScope scope,
            String question,
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    ) {
        return generate(
                scope,
                question,
                documents,
                List.of(),
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
        return generate(
                scope,
                question,
                retrieval.documents(),
                retrieval.items(),
                onDelta
        );
    }

    private List<Long> generate(
            SearchToolScope scope,
            String question,
            List<SearchDocumentResult> documents,
            List<Item> items,
            Consumer<String> onDelta
    ) {
        access.validate(scope);
        boolean fullDocumentsOnly = !documents.isEmpty()
                && springAi.filter(model -> model.supports(scope.workspaceId()))
                        .isPresent();
        List<Map<String, Object>> previous = history.beforeQuestion(scope);
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                properties.getContextCharacterLimit(),
                question,
                scope.startedAt(),
                fullDocumentsOnly,
                items
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
                        items,
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
                "Search context budget workspaceId={} answerId={} inputChars={} limitChars={} documents={} selectedDocuments={} history={} selectedHistory={} partial={}",
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
                budget.partial()
        );
        SearchAgentContext context = new SearchAgentContext(
                properties,
                mapper
        );
        context.validate(messages);
        for (int attempt = 0; attempt < 2; attempt++) {
            StringBuilder validatedText = new StringBuilder();
            SearchAnswerJsonStream answer = new SearchAnswerJsonStream(
                    mapper,
                    validatedText::append
            );
            List<Long> ids;
            try {
                stream(
                        scope,
                        messages,
                        fullDocumentsOnly,
                        text -> {
                            access.validate(scope);
                            answer.accept(text);
                        }
                );
                access.validate(scope);
                ids = answer.complete(budget.documents());
            } catch (RuntimeException exception) {
                if (ServerErrorLogger.isControlFlow(exception)) {
                    throw exception;
                }
                ServerErrorLogger.recordTaskFailure(
                        log,
                        "Search answer phase=validation attempt=" + (attempt + 1),
                        exception
                );
                if (attempt == 0 && retryable(exception)) {
                    continue;
                }
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
                        items,
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
            // Publish only once the entire response and every evidence ID have been
            // validated.
            // Consumer/persistence errors are handled by the worker, never retried as model
            // errors.
            onDelta.accept(validatedText.toString());
            publishCoverage(
                    items,
                    ids,
                    budget.documentsLimited(),
                    onDelta
            );
            log.info(
                    "Search answer outcome=generated workspaceId={} answerId={}",
                    scope.workspaceId(),
                    scope.answerId()
            );
            return ids;
        }
        throw new IllegalStateException("Unreachable answer attempt");
    }

    private void publishCoverage(
            List<Item> items,
            List<Long> citedIds,
            boolean documentsLimited,
            Consumer<String> onDelta
    ) {
        StringBuilder guidance = new StringBuilder();
        for (Item item : items) {
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

    private boolean retryable(RuntimeException exception) {
        if (!(exception instanceof SearchToolException tool)) {
            return false;
        }
        return java.util.Set.of(
                "SEARCH_UPSTREAM_UNAVAILABLE",
                "SEARCH_TRANSPORT_FAILED",
                "INCOMPLETE_MODEL_STREAM",
                "INVALID_MODEL_ANSWER",
                "INVALID_MODEL_RESPONSE",
                "MODEL_GENERATION_FAILED"
        )
                .contains(tool.getCode());
    }
}
