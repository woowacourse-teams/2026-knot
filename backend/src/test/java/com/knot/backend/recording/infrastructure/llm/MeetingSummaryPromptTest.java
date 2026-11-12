package com.knot.backend.recording.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MeetingSummaryPromptTest {

    @Test
    @DisplayName("전체 정리와 회의 결론을 먼저 쓰고 결정·미결정 요약을 마지막에 둔다")
    void createRequest_success_ordersDetailedSummaryBeforeDecisionOverview() {
        String system = new MeetingSummaryPrompt(new ObjectMapper()).createRequest("서비스 소개를 공유했다.")
                .messages()
                .getFirst()
                .content();
        int section = system.indexOf("### 6. 출력 템플릿");
        int start = system.indexOf(
                "```",
                section
        ) + 3;
        String template = system.substring(
                start,
                system.indexOf(
                        "```",
                        start
                )
        );

        assertThat(template).containsSubsequence(
                "## 전체 정리",
                "### 1.",
                "회의 핵심 결론:",
                "## 한눈에 보기",
                "### 정한 방향",
                "### 아직 결정하지 못한 것"
        );
        assertThat(system).doesNotContain(
                "첫 줄은 `## 한눈에 보기`",
                "마지막 줄은 `회의 핵심 결론:`",
                "이 마지막 줄이 없으면"
        );
    }
}
