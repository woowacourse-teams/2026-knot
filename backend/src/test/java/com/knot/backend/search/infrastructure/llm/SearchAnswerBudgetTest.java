package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchAnswerBudgetTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final int LIMIT = 18_000;

    private final JsonMapper mapper = JsonMapper.builder()
            .build();

    @Test
    @DisplayName("긴 문서와 이력을 실제 직렬화 한도 안에 넣고 부분 범위를 알린다")
    void select_success_limitsEvidenceAndHistory() {
        // given
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                8000,
                "어제 회의 요약"
        );
        List<SearchDocumentResult> documents = List.of(
                new SearchDocumentResult(
                        1,
                        "회의",
                        "주제",
                        "가\"\\".repeat(5000)
                ),
                new SearchDocumentResult(
                        2,
                        "회의2",
                        "주제",
                        "나".repeat(5000)
                )
        );
        List<Map<String, Object>> history = List.of(
                Map.of(
                        "role",
                        "user",
                        "content",
                        "이전".repeat(6000)
                )
        );
        // when
        budget.select(
                documents,
                history
        );
        // then
        assertThat(
                mapper.writeValueAsString(budget.messages())
                        .length()
        ).isLessThanOrEqualTo(8000);
        assertThat(budget.documents()).hasSize(1);
        assertThat(
                budget.documents()
                        .getFirst()
                        .content()
        ).contains("본문 일부만 제공됨");
        assertThat(
                budget.messages()
                        .getFirst()
                        .get("content")
                        .toString()
        ).contains("일부 문서");
        assertThat(budget.historyCount()).isZero();
    }

    @Test
    @DisplayName("스무 개의 긴 검색 문서를 예산 내에 넣으며 누락을 표시한다")
    void select_success_boundsTwentyDocuments() {
        // given
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                12000,
                "회의 요약"
        );
        List<SearchDocumentResult> documents = java.util.stream.LongStream.rangeClosed(
                1,
                20
        )
                .mapToObj(
                        id -> new SearchDocumentResult(
                                id,
                                "회의",
                                "주제",
                                "내용".repeat(1500)
                        )
                )
                .toList();
        // when
        budget.select(
                documents,
                List.of()
        );
        // then
        assertThat(budget.documents()).isNotEmpty()
                .hasSizeLessThan(20);
        assertThat(budget.partial()).isTrue();
        assertThat(
                mapper.writeValueAsString(budget.messages())
                        .length()
        ).isLessThanOrEqualTo(12000);
    }

    @Test
    @DisplayName("문서 생성일과 회의 시각 및 질문 시각을 서울 시간으로 구분해 전달한다")
    void select_success_includesDocumentDates() {
        // given
        Instant recorded = Instant.parse("2026-10-27T14:50:00Z");
        Instant created = Instant.parse("2026-10-27T15:10:00Z");
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                48000,
                "어제 회의",
                created
        );
        SearchDocumentResult document = new SearchDocumentResult(
                1,
                "제목",
                "주제",
                "내용",
                recorded,
                created
        );
        // when
        budget.select(
                List.of(document),
                List.of()
        );
        // then
        String user = budget.messages()
                .getLast()
                .get("content")
                .toString();
        assertThat(user).contains(
                "2026-10-27T23:50+09:00",
                "2026-10-28T00:10+09:00",
                "documentCreatedAt",
                "recordingStartedAt",
                "requestedAt"
        );
    }

    @Test
    @DisplayName("기본 요청만으로 예산을 넘으면 모델 호출 전에 실패한다")
    void select_failure_baseRequestExceedsBudget() {
        // given
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                10,
                "질문"
        );
        // when & then
        assertThatThrownBy(
                () -> budget.select(
                        List.of(),
                        List.of()
                )
        ).hasMessage("SEARCH_CONTEXT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("예산 안의 문서와 대화는 그대로 보존한다")
    void select_success_preservesCompleteEvidence() {
        // given
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                48000,
                "질문"
        );
        SearchDocumentResult document = new SearchDocumentResult(
                1,
                "제목",
                "주제",
                "내용"
        );
        // when
        budget.select(
                List.of(document),
                List.of(
                        Map.of(
                                "role",
                                "user",
                                "content",
                                "이전"
                        )
                )
        );
        // then
        assertThat(budget.documents()).containsExactly(document);
        assertThat(budget.historyCount()).isEqualTo(1);
        assertThat(budget.partial()).isFalse();
    }

    @Test
    @DisplayName("전문을 담지 못한 문서는 제외하고 뒤 문서의 미정 조건을 전문 그대로 전달한다")
    void select_success_keepsCompleteDocumentsWithinBudget() {
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                LIMIT,
                "결정과 조건은?",
                NOW,
                true,
                List.of()
        );
        SearchDocumentResult large = new SearchDocumentResult(
                1,
                "긴 회의",
                "배포",
                "가".repeat(LIMIT)
        );
        SearchDocumentResult evidence = new SearchDocumentResult(
                2,
                "신규 회의",
                "모니터링",
                "배포 때 함께 알린다.\n구체적인 적용 범위는 아직 미정이다."
        );

        budget.select(
                List.of(
                        large,
                        evidence
                ),
                List.of()
        );

        assertThat(budget.documents()).containsExactly(evidence);
        assertThat(budget.partial()).isTrue();
    }

    @Test
    @DisplayName("전문을 담을 수 없으면 같은 문서에서 질문에 필요한 결정과 조건을 보존한다")
    void select_success_recoversRelevantSectionFromLongDocument() {
        String answer = "이미지 링크는 제외하고 글만 이관한다. 방식은 미정이다.";
        SearchDocumentResult evidence = new SearchDocumentResult(
                105,
                "11월 12일 마이그레이션 회의",
                "이관",
                "## 배경\n" + "일반 검토 ".repeat(LIMIT) + "\n\n## 이관 결정 및 조건\n" + answer
        );
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                LIMIT,
                "11월 12일 마이그레이션 회의에서 이관할 자료와 제외 조건은?",
                NOW,
                true,
                List.of()
        );

        budget.select(
                List.of(evidence),
                List.of()
        );

        assertThat(budget.documents()).extracting(SearchDocumentResult::documentId)
                .containsExactly(105L);
        assertThat(
                budget.documents()
                        .getFirst()
                        .content()
        ).contains(answer)
                .doesNotContain("일반 검토");
        assertThat(mapper.writeValueAsString(budget.messages())).contains(answer)
                .hasSizeLessThanOrEqualTo(LIMIT);
        assertThat(budget.documentsLimited()).isTrue();
    }

    @Test
    @DisplayName("전문을 하나도 담을 수 없으면 제한을 초과한 것으로 판정한다")
    void select_failure_rejectsContextWithoutCompleteDocument() {
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                LIMIT,
                "결정과 조건은?",
                NOW,
                true,
                List.of()
        );
        SearchDocumentResult large = new SearchDocumentResult(
                1,
                "긴 회의",
                "배포",
                "가".repeat(LIMIT)
        );

        assertThatThrownBy(
                () -> budget.select(
                        List.of(large),
                        List.of()
                )
        ).hasMessage("SEARCH_CONTEXT_LIMIT_EXCEEDED");
        assertThat(budget.documents()).isEmpty();
    }
}
