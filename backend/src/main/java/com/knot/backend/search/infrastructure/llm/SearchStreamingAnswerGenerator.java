package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchAnswerGenerator;
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
        SearchAnswerJsonStream answer = new SearchAnswerJsonStream(
                mapper,
                text -> {
                    access.validate(scope);
                    onDelta.accept(text);
                }
        );
        llm.stream(
                scope,
                messages,
                text -> {
                    access.validate(scope);
                    answer.accept(text);
                }
        );
        access.validate(scope);
        return answer.complete(budget.documents());
    }

}
