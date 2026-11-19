package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchNotionAnswerResponseTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .build();
    private final List<SearchDocumentResult> documents = List.of(
            new SearchDocumentResult(
                    7,
                    "기획 회의",
                    "출시",
                    "본문"
            )
    );

    @Test
    @DisplayName("답변 AI가 낸 노션 근거 번호를 낸 순서대로 페이지 위치로 읽는다")
    void complete_success_notionEvidenceInModelOrder() {
        SearchAnswerResponse response = complete(
                answer(
                        "\"ANSWERED\"",
                        "\"출시일은 12월 8일이에요.\"",
                        "[\"E7\"]",
                        "[\"N2\",\"N1\"]",
                        null,
                        null
                ),
                2,
                false
        );
        assertThat(response.usedDocumentIds()).containsExactly(7L);
        assertThat(response.notionEvidenceIndexes()).containsExactly(
                1,
                0
        );
        assertThat(response.notionDraft()).isNull();
    }

    @Test
    @DisplayName("노션만으로 답한 답변은 회의 문서 근거 없이 완료한다")
    void complete_success_notionOnlyAnswer() {
        SearchAnswerResponse response = complete(
                answer(
                        "\"ANSWERED\"",
                        "\"노션 출시 계획에는 12월이라고 적혀 있어요.\"",
                        "[]",
                        "[\"N1\"]",
                        null,
                        null
                ),
                1,
                false
        );
        assertThat(response.usedDocumentIds()).isEmpty();
        assertThat(response.notionEvidenceIndexes()).containsExactly(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"N3\"]", "[\"N1\",\"N1\"]", "[\"N0\"]", "[\"E7\"]", "[1]", "\"N1\""})
    @DisplayName("넘기지 않은 노션 페이지 번호와 중복 번호는 답변으로 받지 않는다")
    void complete_failure_unknownNotionEvidence(String notionIds) {
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"ANSWERED\"",
                                "\"답변\"",
                                "[\"E7\"]",
                                notionIds,
                                null,
                                null
                        ),
                        2,
                        false
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("일반 대화와 찾지 못함 답변에는 노션 근거를 붙이지 않는다")
    void complete_failure_generalWithNotionEvidence() {
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"GENERAL\"",
                                "\"안녕하세요\"",
                                "[]",
                                "[\"N1\"]",
                                null,
                                null
                        ),
                        1,
                        false
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("노션 페이지를 넘겼는데 노션 근거 칸이 없거나 넘기지 않았는데 있으면 받지 않는다")
    void complete_failure_notionFieldMismatch() {
        String withoutNotion = answer(
                "\"ANSWERED\"",
                "\"답변\"",
                "[\"E7\"]",
                null,
                null,
                null
        );
        assertThatThrownBy(
                () -> complete(
                        withoutNotion,
                        1,
                        false
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"ANSWERED\"",
                                "\"답변\"",
                                "[\"E7\"]",
                                "[]",
                                null,
                                null
                        ),
                        0,
                        false
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("초안은 제목, 본문, 근거 회의 문서를 읽는다")
    void complete_success_draft() {
        SearchAnswerResponse response = complete(
                answer(
                        "\"ANSWERED\"",
                        "\"회의록을 노션 페이지로 만들 준비를 했어요.\"",
                        "[\"E7\"]",
                        null,
                        "{\"title\":\" 기획 회의 \",\"body\":\"## 결정\\n- 출시일 12월 8일\",\"evidenceId\":\"E7\"}",
                        "null"
                ),
                0,
                true
        );
        assertThat(response.notionDraft()).isEqualTo(
                new SearchAnswerResponse.Draft(
                        "기획 회의",
                        "## 결정\n- 출시일 12월 8일",
                        7
                )
        );
        assertThat(response.notionDraftIssue()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\" \",\"body\":\"본문\",\"evidenceId\":\"E7\"}",
            "{\"title\":\"제목\",\"body\":\"본문\",\"evidenceId\":\"E8\"}", "{\"title\":\"제목\",\"body\":\"본문\"}",
            "{\"title\":\"제목\",\"body\":null,\"evidenceId\":\"E7\"}", "\"초안\""})
    @DisplayName("제목이 비었거나 넘기지 않은 회의 문서로 쓴 초안은 받지 않는다")
    void complete_failure_invalidDraft(String draft) {
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"ANSWERED\"",
                                "\"답변\"",
                                "[\"E7\"]",
                                null,
                                draft,
                                "null"
                        ),
                        0,
                        true
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("초안을 쓰지 못한 사유가 있으면 답글을 비울 수 있고 초안과 사유를 함께 받지 않는다")
    void complete_success_draftIssueAllowsBlankAnswer() {
        SearchAnswerResponse response = complete(
                answer(
                        "\"GENERAL\"",
                        "\"\"",
                        "[]",
                        null,
                        "null",
                        "\"MULTIPLE_PAGES\""
                ),
                0,
                true
        );
        assertThat(response.notionDraftIssue()).isEqualTo("MULTIPLE_PAGES");
        assertThat(response.notionDraft()).isNull();
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"GENERAL\"",
                                "\"\"",
                                "[]",
                                null,
                                "null",
                                "null"
                        ),
                        0,
                        true
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"ANSWERED\"",
                                "\"답변\"",
                                "[\"E7\"]",
                                null,
                                "{\"title\":\"제목\",\"body\":\"본문\",\"evidenceId\":\"E7\"}",
                                "\"MULTIPLE_PAGES\""
                        ),
                        0,
                        true
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
        assertThatThrownBy(
                () -> complete(
                        answer(
                                "\"ANSWERED\"",
                                "\"답변\"",
                                "[\"E7\"]",
                                null,
                                "null",
                                "\"ALL_MEETINGS\""
                        ),
                        0,
                        true
                )
        ).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("노션 칸을 더한 출력 형식은 답글을 초안보다 앞에 두고 모든 칸을 필수로 둔다")
    void responseFormat_success_ordersAnswerBeforeDraft() {
        JsonNode schema = mapper.valueToTree(
                SearchAnswerSchema.responseFormat(
                        true,
                        true,
                        true
                )
        )
                .path("json_schema")
                .path("schema");
        List<String> names = new ArrayList<>(
                schema.path("properties")
                        .propertyNames()
        );
        assertThat(names).containsExactly(
                "status",
                "decisionState",
                "answer",
                "evidenceIds",
                "notionEvidenceIds",
                "clarificationQuestion",
                "notionDraft",
                "notionDraftIssue"
        );
        assertThat(
                mapper.convertValue(
                        schema.path("required"),
                        List.class
                )
        ).isEqualTo(names);
        assertThat(
                schema.path("properties")
                        .path("evidenceIds")
                        .path("maxItems")
                        .asInt()
        ).isEqualTo(3);
    }

    @Test
    @DisplayName("노션 칸이 없는 출력 형식은 기존 다섯 칸과 같다")
    void responseFormat_success_withoutNotionKeepsFiveFields() {
        Map<String, Object> format = SearchAnswerSchema.responseFormat(
                false,
                false,
                false
        );
        JsonNode schema = mapper.valueToTree(format)
                .path("json_schema")
                .path("schema");
        assertThat(schema.path("required")).hasSize(5);
        assertThat(
                schema.path("properties")
                        .has("notionEvidenceIds")
        ).isFalse();
    }

    private SearchAnswerResponse complete(
            String json,
            int notionPageCount,
            boolean draftRequested
    ) {
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                text -> {
                }
        );
        stream.accept(json);
        return stream.complete(
                documents,
                notionPageCount,
                draftRequested
        );
    }

    private String answer(
            String status,
            String answer,
            String evidenceIds,
            String notionIds,
            String draft,
            String issue
    ) {
        StringBuilder json = new StringBuilder("{\"status\":").append(status)
                .append(",\"decisionState\":\"NOT_APPLICABLE\",\"answer\":")
                .append(answer)
                .append(",\"evidenceIds\":")
                .append(evidenceIds);
        appendField(
                json,
                "notionEvidenceIds",
                notionIds
        );
        json.append(",\"clarificationQuestion\":null");
        appendField(
                json,
                "notionDraft",
                draft
        );
        appendField(
                json,
                "notionDraftIssue",
                issue
        );
        return json.append("}")
                .toString();
    }

    private void appendField(
            StringBuilder json,
            String name,
            String value
    ) {
        if (value == null) {
            return;
        }
        json.append(",\"")
                .append(name)
                .append("\":")
                .append(value);
    }
}
