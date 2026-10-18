package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchQueryNormalizerTest {

    private final SearchQueryNormalizer normalizer = new SearchQueryNormalizer();

    @Test
    @DisplayName("구어체 질문에서 핵심 검색어를 남긴다")
    void normalize_success_extractsKeywordsFromQ11() {
        // given
        String question = "운영 로그는 며칠 보관하기로 기록돼 있나?";

        // when
        String query = normalizer.normalize(question);

        // then
        assertThat(query).isEqualTo("운영 로그 며칠 보관 기록");
    }

    @Test
    @DisplayName("정규화 후 질문이 비어 있으면 빈 문자열을 반환한다")
    void normalize_success_returnsEmptyWhenOnlyQuestionFillersRemain() {
        // given
        String question = "누구인가요?";

        // when
        String query = normalizer.normalize(question);

        // then
        assertThat(query).isEmpty();
    }

    @Test
    @DisplayName("조사와 활용형 질문 문구를 핵심 검색어로 정리한다")
    void normalize_success_removesKoreanParticlesAndQuestionEnding() {
        // given
        String question = "서비스 오류는 누구 담당으로 결정됐나요?";

        // when
        String query = normalizer.normalize(question);

        // then
        assertThat(query).isEqualTo("서비스 오류 담당 결정");
    }
}
