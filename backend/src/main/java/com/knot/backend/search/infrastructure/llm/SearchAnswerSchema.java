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
        return format(
                properties(includeArrayLimit),
                List.of(
                        "status",
                        "decisionState",
                        "answer",
                        "evidenceIds",
                        "clarificationQuestion"
                )
        );
    }

    /**
     * 노션 페이지를 넘기면 notionEvidenceIds를, 노션 페이지 만들기 초안을 받으면 notionDraft와
     * notionDraftIssue를 더한다. 답글 스트리밍이 초안을 기다리지 않게 answer를 앞에, 긴 초안을 맨 뒤에 둔다.
     */
    public static Map<String, Object> responseFormat(
            boolean includeArrayLimit,
            boolean notionPages,
            boolean notionDraft
    ) {
        Map<String, Object> base = properties(includeArrayLimit);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String name : List.of(
                "status",
                "decisionState",
                "answer",
                "evidenceIds"
        )) {
            ordered.put(
                    name,
                    base.get(name)
            );
        }
        if (notionPages) {
            ordered.put(
                    "notionEvidenceIds",
                    ids("^N[0-9]+$")
            );
        }
        ordered.put(
                "clarificationQuestion",
                base.get("clarificationQuestion")
        );
        if (notionDraft) {
            ordered.put(
                    "notionDraft",
                    nullable(draft())
            );
            ordered.put(
                    "notionDraftIssue",
                    nullable(
                            Map.of(
                                    "type",
                                    "string",
                                    "enum",
                                    SearchAnswerResponse.DRAFT_ISSUES
                            )
                    )
            );
        }
        return format(
                ordered,
                List.copyOf(ordered.keySet())
        );
    }

    private static Map<String, Object> draft() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "title",
                Map.of(
                        "type",
                        "string"
                )
        );
        properties.put(
                "body",
                Map.of(
                        "type",
                        "string"
                )
        );
        properties.put(
                "evidenceId",
                Map.of(
                        "type",
                        "string",
                        "pattern",
                        "^E[0-9]+$"
                )
        );
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.copyOf(properties.keySet()),
                "additionalProperties",
                false
        );
    }

    private static Map<String, Object> ids(String pattern) {
        return Map.of(
                "type",
                "array",
                "items",
                Map.of(
                        "type",
                        "string",
                        "pattern",
                        pattern
                )
        );
    }

    private static Map<String, Object> nullable(Map<String, Object> value) {
        return Map.of(
                "anyOf",
                List.of(
                        value,
                        Map.of(
                                "type",
                                "null"
                        )
                )
        );
    }

    private static Map<String, Object> properties(boolean includeArrayLimit) {
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
        return Map.of(
                "status",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "GENERAL",
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
    }

    private static Map<String, Object> format(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
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
