package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchToolException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record SearchAnswerResponse(
        String answer,
        List<Long> usedDocumentIds,
        String clarificationQuestion
) {
    private static final Set<String> FIELDS = Set.of(
            "status",
            "decisionState",
            "answer",
            "evidenceIds",
            "clarificationQuestion"
    );
    private static final Set<String> STATUSES = Set.of(
            "GENERAL",
            "ANSWERED",
            "PARTIAL",
            "CONFLICT",
            "NOT_FOUND",
            "NEEDS_CLARIFICATION"
    );
    private static final Set<String> DECISIONS = Set.of(
            "DECIDED",
            "DEFERRED",
            "UNDECIDED",
            "PROPOSED",
            "ACTION",
            "UNKNOWN",
            "NOT_APPLICABLE"
    );

    public static SearchAnswerResponse parse(
            String json,
            List<SearchDocumentResult> documents,
            ObjectMapper mapper
    ) {
        JsonNode response = read(
                json,
                mapper
        );
        validateFields(response);
        String status = response.path("status")
                .asString();
        validateValue(
                response.path("status"),
                STATUSES
        );
        validateValue(
                response.path("decisionState"),
                DECISIONS
        );
        validateAnswer(
                response,
                status
        );
        validateClarification(
                response,
                status
        );
        List<Long> ids = usedIds(
                response.path("evidenceIds"),
                documents
        );
        validateEvidenceStatus(
                response,
                status,
                ids
        );
        return new SearchAnswerResponse(
                response.path("answer")
                        .asString(),
                ids,
                response.path("clarificationQuestion")
                        .asString(null)
        );
    }

    private static JsonNode read(
            String json,
            ObjectMapper mapper
    ) {
        try {
            return mapper.reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
        } catch (JacksonException exception) {
            throw invalid(exception);
        }
    }

    private static void validateFields(JsonNode response) {
        if (response == null || !response.isObject() || response.size() != FIELDS.size()) {
            throw invalid();
        }
        for (String field : FIELDS) {
            if (!response.has(field)) {
                throw invalid();
            }
        }
    }

    private static void validateValue(
            JsonNode value,
            Set<String> allowed
    ) {
        if (!value.isString() || !allowed.contains(value.asString())) {
            throw invalid();
        }
    }

    private static void validateAnswer(
            JsonNode response,
            String status
    ) {
        JsonNode answer = response.path("answer");
        if (!answer.isString()) {
            throw invalid();
        }
        if (!"NEEDS_CLARIFICATION".equals(status) && answer.asString()
                .isBlank()) {
            throw invalid();
        }
    }

    private static void validateClarification(
            JsonNode response,
            String status
    ) {
        JsonNode question = response.path("clarificationQuestion");
        if ("NEEDS_CLARIFICATION".equals(status)) {
            validateQuestion(question);
            return;
        }
        if (!question.isNull()) {
            throw invalid();
        }
    }

    private static List<Long> usedIds(
            JsonNode ids,
            List<SearchDocumentResult> documents
    ) {
        if (!ids.isArray() || ids.size() > 3) {
            throw invalid();
        }
        Map<String, Long> allowed = documents.stream()
                .collect(
                        Collectors.toMap(
                                document -> "E" + document.documentId(),
                                SearchDocumentResult::documentId
                        )
                );
        Set<String> unique = new HashSet<>();
        List<Long> result = new ArrayList<>();
        for (JsonNode id : ids) {
            validateEvidenceId(
                    id,
                    allowed,
                    unique
            );
            result.add(allowed.get(id.asString()));
        }
        return List.copyOf(result);
    }

    private static void validateEvidenceStatus(
            JsonNode response,
            String status,
            List<Long> ids
    ) {
        if ("GENERAL".equals(status)) {
            if (!ids.isEmpty() || !"NOT_APPLICABLE".equals(
                    response.path("decisionState")
                            .asString()
            )) {
                throw invalid();
            }
            return;
        }
        if ("NOT_FOUND".equals(status)) {
            validateNotFound(
                    response,
                    ids
            );
            return;
        }
        if (!"NEEDS_CLARIFICATION".equals(status) && ids.isEmpty()) {
            throw invalid();
        }
    }

    private static void validateQuestion(JsonNode question) {
        if (!question.isString() || question.asString()
                .isBlank()) {
            throw invalid();
        }
    }

    private static void validateEvidenceId(
            JsonNode id,
            Map<String, Long> allowed,
            Set<String> unique
    ) {
        if (!id.isString()) {
            throw invalid();
        }
        if (!allowed.containsKey(id.asString())) {
            throw invalid();
        }
        if (!unique.add(id.asString())) {
            throw invalid();
        }
    }

    private static void validateNotFound(
            JsonNode response,
            List<Long> ids
    ) {
        if (!ids.isEmpty()) {
            throw invalid();
        }
        if (!"UNKNOWN".equals(
                response.path("decisionState")
                        .asString()
        )) {
            throw invalid();
        }
    }

    private static SearchToolException invalid() {
        return new SearchToolException("INVALID_MODEL_ANSWER");
    }

    private static SearchToolException invalid(Throwable cause) {
        return new SearchToolException(
                "INVALID_MODEL_ANSWER",
                cause
        );
    }
}
