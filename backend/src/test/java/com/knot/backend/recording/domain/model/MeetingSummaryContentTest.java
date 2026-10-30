package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingSummaryContentTest {
    @Test
    @DisplayName("내용을 바꾸지 않고 제목과 결론의 마크다운 표현을 표준화한다")
    void normalize_formatVariants() {
        String value = "\uFEFF# 한눈에 보기\r\n### 정한 방향\r\n- 1,200명 초과 시 진행한다.\r\n- **회의 핵심 결론:** 1,200명 초과 시 진행한다.";

        String result = MeetingSummaryContent.normalize(value);

        assertThat(result).isEqualTo("## 한눈에 보기\n### 정한 방향\n- 1,200명 초과 시 진행한다.\n회의 핵심 결론: 1,200명 초과 시 진행한다.");
        assertThat(MeetingSummaryContent.normalize(result)).isEqualTo(result);
    }

    @Test
    @DisplayName("줄바꿈이 모두 빠진 요약의 명확한 제목과 결론 경계를 복구한다")
    void normalize_joinedLines() {
        String value = "## 한눈에 보기### 정한 방향- 조건을 유지한다.## 1. 배경- 논의했다.회의 핵심 결론: 조건을 유지한다.";

        assertThat(MeetingSummaryContent.normalize(value))
                .isEqualTo("## 한눈에 보기\n### 정한 방향\n- 조건을 유지한다.\n## 1. 배경\n- 논의했다.\n회의 핵심 결론: 조건을 유지한다.");
    }

    @Test
    @DisplayName("서식이 모호한 문구를 추측해 바꾸지 않고 보존한다")
    void normalize_ambiguousConclusion() {
        assertThat(MeetingSummaryContent.normalize("## 한눈에 보기\n방향: 회의 핵심 결론을 논의했다.\n다른 결론도 논의했다.")).contains(
                "방향: 회의 핵심 결론을 논의했다.",
                "다른 결론도 논의했다."
        );
    }

    @Test
    @DisplayName("결론이 비어 있어도 본문은 보존한다")
    void normalize_blankConclusion() {
        assertThat(MeetingSummaryContent.normalize("## 한눈에 보기\n내용\n회의 핵심 결론: \u00a0")).contains("내용");
    }

    @Test
    @DisplayName("제목이나 결론이 없어도 본문은 보존하고 빈 내용은 거부한다")
    void normalize_missingStructure() {
        assertThat(MeetingSummaryContent.normalize("요약 내용\n회의 핵심 결론: 내용")).contains("요약 내용");
        assertThat(MeetingSummaryContent.normalize("## 한눈에 보기\n내용")).contains("내용");
        assertThatThrownBy(() -> MeetingSummaryContent.normalize(" \u00a0 ")).isInstanceOf(RecordingException.class);
        assertThatThrownBy(() -> MeetingSummaryContent.normalize("## 한눈에 보기\n### 정한 방향"))
                .isInstanceOf(RecordingException.class);
    }
}
