package com.knot.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import com.knot.backend.search.infrastructure.llm.SearchFastPathDocumentRetriever;
import com.knot.backend.search.infrastructure.llm.SearchLlmClient;
import com.knot.backend.search.infrastructure.llm.SearchMcpDocumentRetriever;
import com.knot.backend.search.infrastructure.mcp.SearchMcpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SearchAgentConfigTest {

    private final SearchAgentConfig config = new SearchAgentConfig();
    private final SearchLlmClient llm = mock(SearchLlmClient.class);
    private final SearchMcpClient mcp = mock(SearchMcpClient.class);
    private final SearchHistoryQuery history = mock(SearchHistoryQuery.class);
    private final SearchToolAccess access = mock(SearchToolAccess.class);
    private final SearchDocumentQuery query = mock(SearchDocumentQuery.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);

    @Test
    @DisplayName("기본 설정은 기존 MCP Agent 검색기를 사용한다")
    void searchDocumentRetriever_success_defaultsToAgent() {
        // given
        SearchAgentProperties properties = new SearchAgentProperties();

        // when
        SearchDocumentRetriever retriever = config.searchDocumentRetriever(
                llm,
                mcp,
                history,
                access,
                properties,
                mapper,
                query
        );

        // then
        assertThat(retriever).isInstanceOf(SearchMcpDocumentRetriever.class);
    }

    @Test
    @DisplayName("Fast Path는 설정에서 명시적으로 선택할 때만 사용한다")
    void searchDocumentRetriever_success_selectsFastPathExplicitly() {
        // given
        SearchAgentProperties properties = new SearchAgentProperties();
        properties.setRetrievalMode(SearchRetrievalMode.FAST_PATH);

        // when
        SearchDocumentRetriever retriever = config.searchDocumentRetriever(
                llm,
                mcp,
                history,
                access,
                properties,
                mapper,
                query
        );

        // then
        assertThat(retriever).isInstanceOf(SearchFastPathDocumentRetriever.class);
    }
}
