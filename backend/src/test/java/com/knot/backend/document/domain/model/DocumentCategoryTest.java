package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentCategoryTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @Test
    @DisplayName("큰 주제 이름의 유니코드 공백과 조합 문자를 정규화한다")
    void create_success_normalizedName() {
        // when
        DocumentCategory category = DocumentCategory.create(
                1,
                "\u00a0녹음   전사\u00a0",
                "음성 처리 범위",
                NOW
        );

        // then
        assertThat(category.getName()).isEqualTo("녹음 전사");
        assertThat(category.getNormalizedName()).isEqualTo("녹음 전사");
        assertThat(category.isFallback()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00a0", "기타", "미분류", "일반"})
    @DisplayName("정상 큰 주제로 빈 값과 최후 수단 이름을 만들지 못한다")
    void create_failure_invalidName(String name) {
        // when & then
        assertThatThrownBy(
                () -> DocumentCategory.create(
                        1,
                        name,
                        "범위",
                        NOW
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("기타는 정상 주제와 구분되는 최후 수단 종류로 생성한다")
    void createFallback_success_reservedCategory() {
        // when
        DocumentCategory category = DocumentCategory.createFallback(
                1,
                NOW
        );

        // then
        assertThat(category.getName()).isEqualTo("기타");
        assertThat(category.isFallback()).isTrue();
    }
}
