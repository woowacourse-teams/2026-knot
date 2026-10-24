package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentCategoryFallbackClassifierTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private final DocumentCategoryFallbackClassifier classifier = new DocumentCategoryFallbackClassifier();

    @Test
    @DisplayName("음성 규칙과 맞는 기존 큰 주제를 먼저 재사용한다")
    void classify_success_reuseBeforeCreation() {
        // given
        DocumentCategory category = category(
                12,
                "녹음 및 전사",
                "음성 업로드와 STT 품질"
        );

        // when
        DocumentCategoryDecision decision = classifier.classify(
                "녹음 안정성",
                "전사 품질 검증",
                "STT 품질을 측정한다.",
                List.of(category)
        );

        // then
        assertThat(decision.getExistingCategoryId()).isEqualTo(12);
        assertThat(decision.getSource()).isEqualTo(DocumentCategoryAssignmentSource.RULE_REUSE);
    }

    @Test
    @DisplayName("기존 주제가 없으면 규칙의 재사용 가능한 큰 주제를 만든다")
    void classify_success_createBroadName() {
        // when
        DocumentCategoryDecision decision = classifier.classify(
                "녹음 수치 실시간 변수화",
                "녹음 수치 실시간 변수화 작업",
                "입력 음성을 녹음한다.",
                List.of()
        );

        // then
        assertThat(decision.getNewName()).isEqualTo("녹음·전사");
        assertThat(decision.getSource()).isEqualTo(DocumentCategoryAssignmentSource.RULE_NEW);
    }

    @Test
    @DisplayName("알 수 없는 안건은 문서 제목을 복사하지 않고 기타를 선택한다")
    void classify_success_unknownUsesFallback() {
        // when
        DocumentCategoryDecision decision = classifier.classify(
                "철학 토론",
                "니체의 최후의 인간",
                "행복과 고통에 관한 토론",
                List.of()
        );

        // then
        assertThat(decision.getSource()).isEqualTo(DocumentCategoryAssignmentSource.FALLBACK);
        assertThat(decision.getExistingCategoryId()).isNull();
    }

    @Test
    @DisplayName("여러 규칙이 동점이면 임의로 한 범위에 넣지 않는다")
    void classify_success_ambiguousUsesFallback() {
        // when
        DocumentCategoryDecision decision = classifier.classify(
                "녹음과 배포",
                "녹음과 배포",
                "녹음과 배포",
                List.of()
        );

        // then
        assertThat(decision.getSource()).isEqualTo(DocumentCategoryAssignmentSource.FALLBACK);
    }

    @Test
    @DisplayName("본문의 배경 언급이 중심 안건과 다른 주제로 옮기지 않는다")
    void classify_success_prioritizeAgenda() {
        // when
        DocumentCategoryDecision decision = classifier.classify(
                "녹음 안정성",
                "녹음 개선",
                "배경으로 배포와 서버 운영을 언급했다.",
                List.of()
        );

        // then
        assertThat(decision.getNewName()).isEqualTo("녹음·전사");
    }

    @Test
    @DisplayName("비슷한 기존 후보가 여러 개면 새 중복 폴더를 생성하지 않는다")
    void classify_success_ambiguousCatalogUsesFallback() {
        // given
        List<DocumentCategory> categories = List.of(
                category(
                        12,
                        "녹음·전사",
                        "음성 처리"
                ),
                category(
                        13,
                        "음성 처리",
                        "STT와 녹음"
                )
        );

        // when
        DocumentCategoryDecision decision = classifier.classify(
                "녹음",
                "녹음",
                "",
                categories
        );

        // then
        assertThat(decision.getSource()).isEqualTo(DocumentCategoryAssignmentSource.FALLBACK);
    }

    private DocumentCategory category(
            long id,
            String name,
            String description
    ) {
        DocumentCategory category = DocumentCategory.create(
                1,
                name,
                description,
                NOW
        );
        ReflectionTestUtils.setField(
                category,
                "id",
                id
        );
        return category;
    }
}
