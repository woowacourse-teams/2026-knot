package com.knot.backend.search.infrastructure.llm;

import java.util.List;
import java.util.Map;

public final class SearchAnswerSchema {

    private SearchAnswerSchema() {}

    public static Map<String, Object> responseFormat() {
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
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        3,
                        "items",
                        Map.of(
                                "type",
                                "string",
                                "pattern",
                                "^E[0-9]+$"
                        )
                ),
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
