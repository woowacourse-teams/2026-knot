package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SearchAnswerJsonStreamTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .build();
    private final List<SearchDocumentResult> documents = List.of(
            new SearchDocumentResult(
                    7,
                    "회의",
                    "결정",
                    "본문"
            )
    );

    @Test
    @DisplayName("일반 대화는 회의 문서 근거 없이 생성한 답변을 완료한다")
    void complete_success_generalConversationWithoutEvidence() {
        List<String> deltas = new ArrayList<>();
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                deltas::add
        );
        stream.accept(
                "{\"status\":\"GENERAL\",\"decisionState\":\"NOT_APPLICABLE\",\"answer\":\"안녕하세요. 무엇을 도와드릴까요?\",\"evidenceIds\":[],\"clarificationQuestion\":null}"
        );
        assertThat(stream.complete(List.of())).isEmpty();
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("안녕하세요. 무엇을 도와드릴까요?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECIDED", "UNKNOWN"})
    @DisplayName("일반 대화 결과로 회의 결정 상태를 확정하지 않는다")
    void complete_failure_generalConversationCannotClaimDecision(String state) {
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                text -> {
                }
        );
        stream.accept(
                "{\"status\":\"GENERAL\",\"decisionState\":\"" + state
                        + "\",\"answer\":\"답변\",\"evidenceIds\":[],\"clarificationQuestion\":null}"
        );
        assertThatThrownBy(() -> stream.complete(List.of())).hasMessage("INVALID_MODEL_ANSWER");
    }

    @Test
    @DisplayName("JSON이 끝나기 전부터 답변만 전달하며 분할된 escape와 이모지를 복구한다")
    void accept_success_partialAnswerAndEscapes() {
        List<String> deltas = new ArrayList<>();
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                deltas::add
        );
        stream.accept("{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"결정");
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("결정");
        stream.accept("\\n\\\"확정\\\" \\uD83");
        stream.accept("D\\uDE");
        stream.accept("00\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null}");
        assertThat(stream.complete(documents)).containsExactly(7L);
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("결정\n\"확정\" 😀");
    }

    @Test
    @DisplayName("다른 필드 안의 answer 문자열을 본문으로 보내지 않고 필드 순서 변경을 허용한다")
    void accept_success_rootAnswerOnly() {
        List<String> deltas = new ArrayList<>();
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                deltas::add
        );
        stream.accept(
                "{\"clarificationQuestion\":null,\"evidenceIds\":[\"E7\"],\"status\":\"ANSWERED\",\"decisionState\":\"NOT_APPLICABLE\",\"answer\":\"answer: 답변\"}"
        );
        assertThat(stream.complete(documents)).containsExactly(7L);
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("answer: 답변");
    }

    @Test
    @DisplayName("확인 질문만 있는 응답은 질문을 화면에 전달한다")
    void complete_success_clarification() {
        List<String> deltas = new ArrayList<>();
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                deltas::add
        );
        stream.accept(
                "{\"status\":\"NEEDS_CLARIFICATION\",\"decisionState\":\"UNKNOWN\",\"answer\":\"\",\"evidenceIds\":[],\"clarificationQuestion\":\"어느 회의인가요?\"}"
        );
        assertThat(stream.complete(documents)).isEmpty();
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("어느 회의인가요?");
    }

    @Test
    @DisplayName("근거 없음 응답은 UNKNOWN과 빈 근거로 완료한다")
    void complete_success_notFound() {
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                text -> {
                }
        );
        stream.accept(
                "{\"status\":\"NOT_FOUND\",\"decisionState\":\"UNKNOWN\",\"answer\":\"현재 검색 가능한 회의 문서에서는 해당 내용을 확인하지 못했습니다.\",\"evidenceIds\":[],\"clarificationQuestion\":null}"
        );
        assertThat(stream.complete(documents)).isEmpty();
    }

    @Test
    @DisplayName("확인 질문과 설명이 분리된 응답에서도 질문을 누락하지 않는다")
    void complete_success_clarificationAfterExplanation() {
        List<String> deltas = new ArrayList<>();
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                deltas::add
        );
        stream.accept(
                "{\"status\":\"NEEDS_CLARIFICATION\",\"decisionState\":\"UNKNOWN\",\"answer\":\"회의를 특정해야 합니다.\",\"evidenceIds\":[],\"clarificationQuestion\":\"어느 회의인가요?\"}"
        );
        assertThat(stream.complete(documents)).isEmpty();
        assertThat(
                String.join(
                        "",
                        deltas
                )
        ).isEqualTo("회의를 특정해야 합니다.\n어느 회의인가요?");
    }

    @Test
    @DisplayName("모든 문자 위치에서 나뉜 JSON을 같은 답변과 근거로 복구한다")
    void accept_success_everyChunkBoundary() {
        String json = "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"첫 줄\\n\\\"둘째\\\" \\uD83D\\uDE00\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null}";
        for (int split = 0; split <= json.length(); split++) {
            List<String> deltas = new ArrayList<>();
            SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                    mapper,
                    deltas::add
            );
            stream.accept(
                    json.substring(
                            0,
                            split
                    )
            );
            stream.accept(json.substring(split));
            assertThat(stream.complete(documents)).containsExactly(7L);
            assertThat(
                    String.join(
                            "",
                            deltas
                    )
            ).isEqualTo("첫 줄\n\"둘째\" 😀");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"E99\"]", "[\"E7\",\"E7\"]", "[7]", "[]", "[\"E7\",\"E8\",\"E9\",\"E10\"]"})
    @DisplayName("읽지 않은 근거·중복·숫자·근거 없는 ANSWERED·네 문서 응답은 거절한다")
    void complete_failure_invalidEvidence(String ids) {
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                text -> {
                }
        );
        stream.accept(
                "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"결정\",\"evidenceIds\":" + ids
                        + ",\"clarificationQuestion\":null}"
        );
        List<SearchDocumentResult> candidates = List.of(
                documents.getFirst(),
                new SearchDocumentResult(
                        8,
                        "회의",
                        "결정",
                        "본문"
                ),
                new SearchDocumentResult(
                        9,
                        "회의",
                        "결정",
                        "본문"
                ),
                new SearchDocumentResult(
                        10,
                        "회의",
                        "결정",
                        "본문"
                )
        );
        assertThatThrownBy(() -> stream.complete(candidates)).hasMessage("INVALID_MODEL_ANSWER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"부분",
            "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"첫째\",\"answer\":\"둘째\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null}",
            "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"본문\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null} {}",
            "{\"status\":\"SUCCESS\",\"decisionState\":\"DECIDED\",\"answer\":\"본문\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null}",
            "{\"status\":\"ANSWERED\",\"decisionState\":\"APPROVED\",\"answer\":\"본문\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":null}",
            "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"본문\",\"evidenceIds\":[\"E7\"],\"clarificationQuestion\":\"질문\"}",
            "{\"status\":\"NOT_FOUND\",\"decisionState\":\"DECIDED\",\"answer\":\"본문\",\"evidenceIds\":[],\"clarificationQuestion\":null}",
            "{\"status\":\"ANSWERED\",\"decisionState\":\"DECIDED\",\"answer\":\"본문\",\"evidenceIds\":[\"E7\"]}"})
    @DisplayName("잘린 JSON·중복 필드·뒤따른 JSON·잘못된 상태 조합은 완료로 인정하지 않는다")
    void complete_failure_invalidContract(String json) {
        SearchAnswerJsonStream stream = new SearchAnswerJsonStream(
                mapper,
                text -> {
                }
        );
        stream.accept(json);
        assertThatThrownBy(() -> stream.complete(documents)).hasMessage("INVALID_MODEL_ANSWER");
    }
}
