package com.knot.backend.search.infrastructure.llm;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class SearchAnswerSchema {

    private SearchAnswerSchema() {}

    public static Map<String, Object> responseFormat() {
        return responseFormat(true);
    }

    public static Map<String, Object> responseFormat(boolean includeArrayLimit) {
        Map<String, Object> evidenceIds = new LinkedHashMap<>(
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of(
                                "type",
                                "string",
                                "pattern",
                                "^E[0-9]+$"
                        )
                )
        );
        if (includeArrayLimit) {
            evidenceIds.put(
                    "maxItems",
                    3
            );
        }
        Map<String, Object> properties = Map.of(
                "status",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "ANSWERED",
                                "PARTIAL",
                                "CONFLICT",
                                "NOT_FOUND",
                                "NEEDS_CLARIFICATION"
                        )
                ),
                "decisionState",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "DECIDED",
                                "DEFERRED",
                                "UNDECIDED",
                                "PROPOSED",
                                "ACTION",
                                "UNKNOWN",
                                "NOT_APPLICABLE"
                        )
                ),
                "answer",
                Map.of(
                        "type",
                        "string"
                ),
                "evidenceIds",
                evidenceIds,
                "clarificationQuestion",
                Map.of(
                        "anyOf",
                        List.of(
                                Map.of(
                                        "type",
                                        "string"
                                ),
                                Map.of(
                                        "type",
                                        "null"
                                )
                        )
                )
        );
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.of(
                        "status",
                        "decisionState",
                        "answer",
                        "evidenceIds",
                        "clarificationQuestion"
                ),
                "additionalProperties",
                false
        );
        return Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of(
                        "name",
                        "search_answer",
                        "strict",
                        true,
                        "schema",
                        schema
                )
        );
    }
}
