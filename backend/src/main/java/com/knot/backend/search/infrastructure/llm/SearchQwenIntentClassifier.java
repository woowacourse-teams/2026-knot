package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class SearchQwenIntentClassifier implements SearchIntentClassifier {

    private final SearchLlmClient llm;
    private final SearchHistoryQuery history;
    private final SearchToolAccess access;
    private final SearchAgentProperties properties;
    private final ObjectMapper mapper;

    @Override
    public SearchUsePlan classify(
            SearchToolScope scope,
            String question
    ) {
        access.validate(scope);
        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "role",
                        "system",
                        "content",
                        SearchIntentPrompt.system()
                ),
                Map.of(
                        "role",
                        "user",
                        "content",
                        mapper.writeValueAsString(
                                Map.of(
                                        "message",
                                        question,
                                        "conversationHistory",
                                        history.beforeQuestion(scope)
                                )
                        )
                )
        );
        new SearchAgentContext(
                properties,
                mapper
        ).validate(messages);
        JsonNode result = llm.intent(
                scope,
                messages
        );
        access.validate(scope);
        return SearchIntentResponse.parse(
                mapper,
                result.path("content")
                        .asString()
        );
    }
}
