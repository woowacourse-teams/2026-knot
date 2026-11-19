package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.SearchToolException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class SearchIntentResponse {
    static final String NOTION_PAGE_READ = "NOTION_PAGE_READ";
    static final String NOTION_PAGE_CREATE = "NOTION_PAGE_CREATE";

    private SearchIntentResponse() {}

    static SearchUsePlan parse(
            ObjectMapper mapper,
            String content
    ) {
        JsonNode response = read(
                mapper,
                content
        );
        if (response == null || !response.isObject() || response.size() != fieldCount(response)) {
            throw invalid();
        }
        SearchIntent intent = intent(response.path("intent"));
        JsonNode tools = response.path("tools");
        if (tools.isMissingNode()) {
            return SearchUsePlan.of(intent);
        }
        validateTools(tools);
        return new SearchUsePlan(
                intent,
                query(
                        tools,
                        NOTION_PAGE_READ
                ).filter(text -> !text.isBlank()),
                query(
                        tools,
                        NOTION_PAGE_CREATE
                )
        );
    }

    /**
     * 분류 응답의 tools 칸 형식. 도구마다 꼬리표와 입력 하나를 받는다.
     */
    static Map<String, Object> toolsSchema() {
        return Map.of(
                "type",
                "array",
                "items",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "tool",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of(
                                                NOTION_PAGE_READ,
                                                NOTION_PAGE_CREATE
                                        )
                                ),
                                "query",
                                Map.of(
                                        "type",
                                        "string"
                                )
                        ),
                        "required",
                        List.of(
                                "tool",
                                "query"
                        ),
                        "additionalProperties",
                        false
                )
        );
    }

    private static int fieldCount(JsonNode response) {
        if (response.has("tools")) {
            return 2;
        }
        return 1;
    }

    private static SearchIntent intent(JsonNode intent) {
        if (!intent.isString()) {
            throw invalid();
        }
        for (SearchIntent candidate : SearchIntent.values()) {
            if (candidate.name()
                    .equals(intent.asString())) {
                return candidate;
            }
        }
        throw invalid();
    }

    private static void validateTools(JsonNode tools) {
        if (!tools.isArray()) {
            throw invalid();
        }
        for (JsonNode tool : tools) {
            validateTool(tool);
        }
    }

    private static void validateTool(JsonNode tool) {
        if (!tool.isObject() || tool.size() != 2 || !tool.path("query")
                .isString()) {
            throw invalid();
        }
        String name = tool.path("tool")
                .asString("");
        if (!NOTION_PAGE_READ.equals(name) && !NOTION_PAGE_CREATE.equals(name)) {
            throw invalid();
        }
    }

    // shortcut: 같은 도구가 여러 번 오면 첫 번째만 쓴다. 한 질문에 노션 검색을 여러 번 해야 하면 그때 바꾼다.
    private static Optional<String> query(
            JsonNode tools,
            String name
    ) {
        for (JsonNode tool : tools) {
            if (name.equals(
                    tool.path("tool")
                            .asString()
            )) {
                return Optional.of(
                        tool.path("query")
                                .asString()
                                .strip()
                );
            }
        }
        return Optional.empty();
    }

    private static JsonNode read(
            ObjectMapper mapper,
            String content
    ) {
        try {
            return mapper.reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(content);
        } catch (JacksonException exception) {
            throw new SearchToolException(
                    "INVALID_INTENT_RESPONSE",
                    exception
            );
        }
    }

    private static SearchToolException invalid() {
        return new SearchToolException("INVALID_INTENT_RESPONSE");
    }
}
