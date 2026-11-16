package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.global.config.LlmProperties;
import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolAccess;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"knot.search.meeting-pipeline.enabled", "knot.search.answer.spring-ai.enabled",
        "knot.llm.enabled", "knot.search.agent.enabled"}, havingValue = "true")
@ConditionalOnProperty(name = "knot.llm.provider", havingValue = "lm-studio", matchIfMissing = true)
class SearchSpringAiConfiguration {

    @Bean
    SearchSpringAiModel searchSpringAiModel(
            LlmProperties llm,
            SearchAgentProperties search,
            ObjectMapper mapper,
            SearchToolAccess access,
            Clock clock,
            @Value("${knot.search.answer.spring-ai.workspace-ids}") List<Long> workspaceIds
    ) {
        return new SearchSpringAiModel(
                llm,
                search,
                mapper,
                access,
                clock,
                workspaceIds
        );
    }
}
