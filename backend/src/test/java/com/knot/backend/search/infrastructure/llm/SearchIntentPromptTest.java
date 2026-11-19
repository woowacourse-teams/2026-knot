package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchIntentPromptTest {

    @Test
    @DisplayName("구체적인 Knot 정책과 동작은 회의 검색으로 분류한다")
    void systemPrompt_routesConcreteKnotFactsToMeetingSearch() {
        String prompt = SearchIntentPrompt.system();

        assertThat(prompt).contains("\"탐색 중에도 녹음이 계속되나요?\" MEETING_SEARCH")
                .contains(
                        "Concrete facts about Knot's actual behavior, policies, decisions, workflows or measured results"
                )
                .contains("Search records")
                .contains("to verify these specifics; do not answer them from general knowledge.")
                .contains("\"Workspace 문서는 누가 접근할 수 있나요?\" MEETING_SEARCH")
                .contains("\"회의 문서 검색 조건을 어떤 범위로 정했나요?\" MEETING_SEARCH")
                .contains("\"답변 근거를 찾을 때 문서 도구를 어떻게 사용하나요?\" MEETING_SEARCH")
                .contains("\"조건을 지정해 문서를 찾고, 후보가 부족하면 어떻게 더 탐색하나요?\" MEETING_SEARCH")
                .contains("\"이 문서는 실제 회의 기록이나 운영 성능 수치인가요?\" MEETING_SEARCH");
    }

    @Test
    @DisplayName("일반적인 Knot 사용 방법만 서비스 안내로 분류한다")
    void systemPrompt_keepsGenericUsageQuestionsInServiceGuide() {
        String prompt = SearchIntentPrompt.system();

        assertThat(prompt)
                .contains(
                        "SERVICE_GUIDE: ONLY generic instructions for operating Knot or high-level explanations of its"
                )
                .contains("\"Knot에서 문서 검색을 어떻게 실행해요?\" SERVICE_GUIDE");
    }

    @Test
    @DisplayName("노션 페이지 만들기 요청은 범위 밖이 아니라 회의 검색과 만들기 도구로 계획한다")
    void systemPrompt_plansNotionToolsInsteadOfOutOfScope() {
        String prompt = SearchIntentPrompt.system();

        assertThat(prompt).contains("Return only JSON: {\"intent\":\"LABEL\",\"tools\":[]}")
                .contains("so this request is never OUT_OF_SCOPE")
                .contains("\"오늘 회의록 노션 회의록 DB에 만들어 줘\" MEETING_SEARCH with")
                .contains("[{\"tool\":\"NOTION_PAGE_CREATE\",\"query\":\"회의록 DB\"}]")
                .contains("[{\"tool\":\"NOTION_PAGE_READ\",\"query\":\"기획서 출시일\"}]");
    }
}
