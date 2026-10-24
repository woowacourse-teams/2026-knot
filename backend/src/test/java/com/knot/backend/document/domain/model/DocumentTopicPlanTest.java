package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.knot.backend.document.domain.DocumentException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class DocumentTopicPlanTest {

    @Test
    @DisplayName("배치별 새 주제 키만 구분하고 원본 계획과 기존 주제는 유지한다")
    void scopeNewKeys_success_preservesOriginalAndExistingKeys() {
        // given
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(
                        1L,
                        "n:1",
                        2L,
                        "e:7"
                ),
                Map.of(),
                Map.of(
                        "n:1",
                        "채용"
                ),
                Map.of(
                        "n:1",
                        "채용 논의"
                )
        );
        // when
        DocumentTopicPlan first = plan.scopeNewKeys(1);
        DocumentTopicPlan second = plan.scopeNewKeys(3);
        // then
        assertThat(first.getAssignments()).containsEntry(
                1L,
                "n:b1-1"
        )
                .containsEntry(
                        2L,
                        "e:7"
                );
        assertThat(second.getAssignments()).containsEntry(
                1L,
                "n:b3-1"
        );
        assertThat(plan.getAssignments()).containsEntry(
                1L,
                "n:1"
        );
    }

    @Test
    @DisplayName("문서 식별자가 잘못된 배치에는 주제 키를 부여하지 않는다")
    void scopeNewKeys_failure_invalidDocumentId() {
        // given
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
        // when & then
        assertThatThrownBy(() -> plan.scopeNewKeys(0)).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("실제로 배정하지 않은 새 주제는 만들지 않는다")
    void validateAssignments_failure_unusedNewCategory() {
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(
                        1L,
                        "e:1"
                ),
                Map.of(),
                Map.of(
                        "n:unused",
                        "문서 생성"
                ),
                Map.of(
                        "n:unused",
                        "문서 정리"
                )
        );
        assertThatThrownBy(
                () -> plan.validateAssignments(
                        Set.of(1L),
                        Set.of("e:1")
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("통합 대상이 다시 다른 대상을 가리키는 연쇄 매핑은 거절한다")
    void validateMapping_failure_transitiveTarget() {
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(),
                Map.of(
                        "e:1",
                        "e:2",
                        "e:2",
                        "e:3",
                        "e:3",
                        "e:3"
                ),
                Map.of(),
                Map.of()
        );
        Set<String> keys = Set.of(
                "e:1",
                "e:2",
                "e:3"
        );
        assertThatThrownBy(
                () -> plan.validateMapping(
                        keys,
                        keys
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("전체 대상에 기존 큰 주제를 배정한다")
    void validateAssignments_success() {
        new DocumentTopicPlan(
                Map.of(
                        1L,
                        "e:3",
                        2L,
                        "e:3"
                ),
                Map.of(),
                Map.of(),
                Map.of()
        ).validateAssignments(
                Set.of(
                        1L,
                        2L
                ),
                Set.of("e:3")
        );
    }

    @Test
    @DisplayName("대상 누락이나 외부 문서가 있으면 전체 계획을 거절한다")
    void validateAssignments_failure_missingTarget() {
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(
                        1L,
                        "e:3"
                ),
                Map.of(),
                Map.of(),
                Map.of()
        );
        assertThatThrownBy(
                () -> plan.validateAssignments(
                        Set.of(
                                1L,
                                2L
                        ),
                        Set.of("e:3")
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("정의하지 않은 주제로 배정하지 않는다")
    void validateAssignments_failure_unknownCategory() {
        DocumentTopicPlan plan = new DocumentTopicPlan(
                Map.of(
                        1L,
                        "e:9"
                ),
                Map.of(),
                Map.of(),
                Map.of()
        );
        assertThatThrownBy(
                () -> plan.validateAssignments(
                        Set.of(1L),
                        Set.of("e:3")
                )
        ).isInstanceOf(DocumentException.class);
    }
}
