package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchAnswerGuidance;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchToolException;
import java.util.List;
import java.util.Map;
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

    @Override
    public List<Long> generate(
            SearchToolScope scope,
            String question,
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    ) {
        access.validate(scope);
        List<Map<String, Object>> previous = history.beforeQuestion(scope);
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                properties.getContextCharacterLimit(),
                question,
                scope.startedAt()
        );
        try {
            budget.select(
                    documents,
                    previous
            );
        } catch (SearchToolException exception) {
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
                llm.stream(
                        scope,
                        messages,
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
                List<Long> related = budget.documents()
                        .stream()
                        .limit(3)
                        .map(SearchDocumentResult::documentId)
                        .toList();
                onDelta.accept(
                        related.isEmpty() ? SearchAnswerGuidance.UNAVAILABLE : SearchAnswerGuidance.RELATED_DOCUMENTS
                );
                log.info(
                        "Search answer outcome=guidance workspaceId={} answerId={}",
                        scope.workspaceId(),
                        scope.answerId()
                );
                return related;
            }
            // Publish only once the entire response and every evidence ID have been
            // validated.
            // Consumer/persistence errors are handled by the worker, never retried as model
            // errors.
            onDelta.accept(validatedText.toString());
            log.info(
                    "Search answer outcome=generated workspaceId={} answerId={}",
                    scope.workspaceId(),
                    scope.answerId()
            );
            return ids;
        }
        throw new IllegalStateException("Unreachable answer attempt");
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
