package com.knot.backend.search.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.search.application.dto.result.SearchUsePlan;
import com.knot.backend.search.domain.model.SearchIntent;
import com.knot.backend.search.domain.SearchToolException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SearchIntentResponseTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .build();

    @ParameterizedTest
    @EnumSource(SearchIntent.class)
    @DisplayName("허용된 의도 하나를 엄격한 JSON에서 읽는다")
    void parse_success_knownIntent(SearchIntent intent) {
        assertThat(
                SearchIntentResponse.parse(
                        mapper,
                        "{\"intent\":\"" + intent.name() + "\"}"
                )
        ).isEqualTo(SearchUsePlan.of(intent));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"intent\":null}", "{\"intent\":1}", "{\"intent\":\"SEARCH\"}",
            "{\"intent\":\"MEETING_SEARCH\",\"answer\":\"케이크\"}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"intent\":\"MEETING_SEARCH\"}", "{\"intent\":\"OUT_OF_SCOPE\"} {}",
            "```json\n{\"intent\":\"OUT_OF_SCOPE\"}\n```", "{\"tools\":[]}",
            "{\"intent\":\"MEETING_SEARCH\",\"tools\":{}}",
            "{\"intent\":\"MEETING_SEARCH\",\"tools\":[{\"tool\":\"NOTION_PAGE_DELETE\",\"query\":\"기획\"}]}",
            "{\"intent\":\"MEETING_SEARCH\",\"tools\":[{\"tool\":\"NOTION_PAGE_READ\"}]}",
            "{\"intent\":\"MEETING_SEARCH\",\"tools\":[{\"tool\":\"NOTION_PAGE_READ\",\"query\":1}]}",
            "{\"intent\":\"MEETING_SEARCH\",\"tools\":[{\"tool\":\"NOTION_PAGE_READ\",\"query\":\"기획\",\"page\":1}]}"})
    @DisplayName("의도 누락, 추가 필드, 중복 키, 추가 JSON, 모르는 도구를 거부한다")
    void parse_failure_invalidJson(String content) {
        assertThatThrownBy(
                () -> SearchIntentResponse.parse(
                        mapper,
                        content
                )
        ).isInstanceOf(SearchToolException.class)
                .hasMessage("INVALID_INTENT_RESPONSE");
    }

    @Test
    @DisplayName("노션 읽기와 만들기를 한 질문의 계획으로 함께 읽는다")
    void parse_success_readAndCreateTools() {
        SearchUsePlan plan = SearchIntentResponse.parse(
                mapper,
                """
                        {"intent":"MEETING_SEARCH","tools":[
                          {"tool":"NOTION_PAGE_READ","query":" 기획서 출시일 "},
                          {"tool":"NOTION_PAGE_CREATE","query":"회의록 DB"}]}
                        """
        );
        assertThat(plan.intent()).isEqualTo(SearchIntent.MEETING_SEARCH);
        assertThat(plan.notionReadQuery()).contains("기획서 출시일");
        assertThat(plan.notionCreateLocation()).contains("회의록 DB");
    }

    @Test
    @DisplayName("위치를 말하지 않은 만들기는 빈 위치로 읽고 회의 문서 찾기를 함께 한다")
    void parse_success_createWithoutLocationSearchesMeetings() {
        SearchUsePlan plan = SearchIntentResponse.parse(
                mapper,
                "{\"intent\":\"OUT_OF_SCOPE\",\"tools\":[{\"tool\":\"NOTION_PAGE_CREATE\",\"query\":\"\"}]}"
        );
        assertThat(plan.intent()).isEqualTo(SearchIntent.MEETING_SEARCH);
        assertThat(plan.notionCreateLocation()).contains("");
        assertThat(plan.notionReadQuery()).isEmpty();
    }

    @Test
    @DisplayName("검색어가 빈 노션 읽기는 계획에 넣지 않고 같은 도구가 여러 번 오면 첫 번째만 쓴다")
    void parse_success_ignoresBlankReadAndKeepsFirstTool() {
        assertThat(
                SearchIntentResponse.parse(
                        mapper,
                        "{\"intent\":\"MEETING_SEARCH\",\"tools\":[{\"tool\":\"NOTION_PAGE_READ\",\"query\":\" \"}]}"
                )
        ).isEqualTo(SearchUsePlan.of(SearchIntent.MEETING_SEARCH));
        assertThat(
                SearchIntentResponse.parse(
                        mapper,
                        """
                                {"intent":"MEETING_SEARCH","tools":[
                                  {"tool":"NOTION_PAGE_READ","query":"출시 계획"},
                                  {"tool":"NOTION_PAGE_READ","query":"회고"}]}
                                """
                )
                        .notionReadQuery()
        ).isEqualTo(Optional.of("출시 계획"));
    }

    @Test
    @DisplayName("빈 도구 목록은 노션을 쓰지 않는 기존 의도와 같다")
    void parse_success_emptyTools() {
        SearchUsePlan plan = SearchIntentResponse.parse(
                mapper,
                "{\"intent\":\"GREETING_OR_THANKS\",\"tools\":[]}"
        );
        assertThat(plan).isEqualTo(SearchUsePlan.of(SearchIntent.GREETING_OR_THANKS));
        assertThat(plan.usesNotion()).isFalse();
    }
}
