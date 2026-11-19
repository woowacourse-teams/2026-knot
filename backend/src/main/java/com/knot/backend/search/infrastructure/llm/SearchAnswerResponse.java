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

/**
 * notionEvidenceIndexes에 넘긴 노션 페이지 목록의 0부터 센 위치를 답변 AI가 낸 순서대로 담는다. 초안을 받지 않았거나
 * 값이 없으면 notionDraft와 notionDraftIssue를 null로 둔다.
 */
public record SearchAnswerResponse(
        String answer,
        List<Long> usedDocumentIds,
        String clarificationQuestion,
        List<Integer> notionEvidenceIndexes,
        Draft notionDraft,
        String notionDraftIssue
) {
    public static final List<String> DRAFT_ISSUES = List.of(
            "MULTIPLE_MEETINGS",
            "MULTIPLE_PAGES",
            "MEETING_NOT_FOUND"
    );
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
        return parse(
                json,
                documents,
                0,
                false,
                mapper
        );
    }

    /**
     * notionPageCount가 0이고 초안을 받지 않으면 기존 다섯 칸 형식과 같다.
     */
    public static SearchAnswerResponse parse(
            String json,
            List<SearchDocumentResult> documents,
            int notionPageCount,
            boolean draftRequested,
            ObjectMapper mapper
    ) {
        JsonNode response = read(
                json,
                mapper
        );
        validateFields(
                response,
                fields(
                        notionPageCount,
                        draftRequested
                )
        );
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
        String issue = draftIssue(response.path("notionDraftIssue"));
        validateAnswer(
                response,
                status,
                issue
        );
        validateClarification(
                response,
                status
        );
        List<Long> ids = usedIds(
                response.path("evidenceIds"),
                documents
        );
        List<Integer> notionIds = notionIds(
                response.path("notionEvidenceIds"),
                notionPageCount
        );
        validateEvidenceStatus(
                response,
                status,
                ids,
                notionIds
        );
        Draft draft = draft(
                response.path("notionDraft"),
                documents
        );
        validateDraft(
                draft,
                issue,
                status
        );
        return new SearchAnswerResponse(
                response.path("answer")
                        .asString(),
                ids,
                response.path("clarificationQuestion")
                        .asString(null),
                notionIds,
                draft,
                issue
        );
    }

    private static Set<String> fields(
            int notionPageCount,
            boolean draftRequested
    ) {
        Set<String> fields = new HashSet<>(FIELDS);
        if (notionPageCount > 0) {
            fields.add("notionEvidenceIds");
        }
        if (draftRequested) {
            fields.add("notionDraft");
            fields.add("notionDraftIssue");
        }
        return fields;
    }

    private static List<Integer> notionIds(
            JsonNode ids,
            int notionPageCount
    ) {
        if (ids.isMissingNode()) {
            return List.of();
        }
        if (!ids.isArray()) {
            throw invalid();
        }
        List<Integer> result = new ArrayList<>();
        for (JsonNode id : ids) {
            int index = notionIndex(
                    id,
                    notionPageCount
            );
            if (result.contains(index)) {
                throw invalid();
            }
            result.add(index);
        }
        return List.copyOf(result);
    }

    private static int notionIndex(
            JsonNode id,
            int notionPageCount
    ) {
        if (!id.isString() || !id.asString()
                .matches("N[1-9][0-9]{0,8}")) {
            throw invalid();
        }
        int number = Integer.parseInt(
                id.asString()
                        .substring(1)
        );
        if (number > notionPageCount) {
            throw invalid();
        }
        return number - 1;
    }

    private static String draftIssue(JsonNode issue) {
        if (issue.isMissingNode() || issue.isNull()) {
            return null;
        }
        if (!issue.isString() || !DRAFT_ISSUES.contains(issue.asString())) {
            throw invalid();
        }
        return issue.asString();
    }

    private static Draft draft(
            JsonNode draft,
            List<SearchDocumentResult> documents
    ) {
        if (draft.isMissingNode() || draft.isNull()) {
            return null;
        }
        if (!draft.isObject() || draft.size() != 3) {
            throw invalid();
        }
        JsonNode title = draft.path("title");
        JsonNode body = draft.path("body");
        if (!title.isString() || title.asString()
                .isBlank() || !body.isString()) {
            throw invalid();
        }
        return new Draft(
                title.asString()
                        .strip(),
                body.asString(),
                draftDocumentId(
                        draft.path("evidenceId"),
                        documents
                )
        );
    }

    private static long draftDocumentId(
            JsonNode evidenceId,
            List<SearchDocumentResult> documents
    ) {
        for (SearchDocumentResult document : documents) {
            if (("E" + document.documentId()).equals(evidenceId.asString(""))) {
                return document.documentId();
            }
        }
        throw invalid();
    }

    private static void validateDraft(
            Draft draft,
            String issue,
            String status
    ) {
        if (draft == null) {
            return;
        }
        if (issue != null || "NEEDS_CLARIFICATION".equals(status)) {
            throw invalid();
        }
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

    private static void validateFields(
            JsonNode response,
            Set<String> fields
    ) {
        if (response == null || !response.isObject() || response.size() != fields.size()) {
            throw invalid();
        }
        for (String field : fields) {
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
            String status,
            String issue
    ) {
        JsonNode answer = response.path("answer");
        if (!answer.isString()) {
            throw invalid();
        }
        // 초안을 쓰지 못한 사유가 있으면 서버가 안내를 붙이므로 다른 질문이 없을 때 답글을 비울 수 있다.
        if (!"NEEDS_CLARIFICATION".equals(status) && issue == null && answer.asString()
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
            List<Long> ids,
            List<Integer> notionIds
    ) {
        if ("GENERAL".equals(status)) {
            validateGeneral(
                    response,
                    ids
            );
            validateNoNotionEvidence(notionIds);
            return;
        }
        if ("NOT_FOUND".equals(status)) {
            validateNotFound(
                    response,
                    ids
            );
            validateNoNotionEvidence(notionIds);
            return;
        }
        if (!"NEEDS_CLARIFICATION".equals(status) && ids.isEmpty() && notionIds.isEmpty()) {
            throw invalid();
        }
    }

    private static void validateNoNotionEvidence(List<Integer> notionIds) {
        if (!notionIds.isEmpty()) {
            throw invalid();
        }
    }

    private static void validateGeneral(
            JsonNode response,
            List<Long> ids
    ) {
        if (!ids.isEmpty() || !"NOT_APPLICABLE".equals(
                response.path("decisionState")
                        .asString()
        )) {
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
        String decision = response.path("decisionState")
                .asString();
        if (!"UNKNOWN".equals(decision) && !"NOT_APPLICABLE".equals(decision)) {
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

    public record Draft(
            String title,
            String body,
            long documentId
    ) {
    }
}
