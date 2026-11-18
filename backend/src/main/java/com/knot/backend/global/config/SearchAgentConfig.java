package com.knot.backend.global.config;

import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchIntentClassifier;
import com.knot.backend.search.infrastructure.llm.SearchQwenIntentClassifier;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.infrastructure.llm.SearchAgentHttp;
import com.knot.backend.search.infrastructure.llm.SearchTransportCancellation;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import com.knot.backend.search.infrastructure.llm.SearchLlmClient;
import com.knot.backend.search.infrastructure.llm.SearchMcpDocumentRetriever;
import com.knot.backend.search.infrastructure.llm.SearchFastPathDocumentRetriever;
import com.knot.backend.search.infrastructure.llm.SearchQueryNormalizer;
import com.knot.backend.search.infrastructure.llm.SearchStreamingAnswerGenerator;
import com.knot.backend.search.infrastructure.llm.SearchSpringAiModel;
import com.knot.backend.search.infrastructure.mcp.SearchMcpClient;
import com.knot.backend.search.infrastructure.index.SearchMeetingHybridRetrieval;
import com.knot.backend.search.infrastructure.mcp.SearchToolTokenCodec;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"knot.search.agent.enabled", "knot.llm.enabled"}, havingValue = "true")
public class SearchAgentConfig {

    @Bean
    public SearchIntentClassifier searchIntentClassifier(
            SearchLlmClient llm,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper
    ) {
        return new SearchQwenIntentClassifier(
                llm,
                history,
                access,
                properties,
                mapper
        );
    }

    @Bean(destroyMethod = "close")
    public SearchTransportCancellation searchTransportCancellation(
            SearchToolAccess access,
            SearchAgentProperties properties
    ) {
        return new SearchTransportCancellation(
                access,
                properties.getCancellationPollInterval()
        );
    }

    @Bean(destroyMethod = "close")
    public SearchAgentHttp searchAgentHttp(
            LlmProperties llm,
            SearchAgentProperties agent,
            SearchWorkerProperties worker,
            ObjectMapper mapper,
            Clock clock,
            SearchTransportCancellation cancellations
    ) {
        agent.validate();
        llm.validateEnabledConfiguration();
        if (agent.getBudget()
                .compareTo(worker.getExecutionLease()) >= 0) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(llm.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SearchAgentHttp(
                client,
                mapper,
                clock,
                llm.getMaxResponseBytes(),
                cancellations
        );
    }

    @Bean
    public SearchLlmClient searchLlmClient(
            SearchAgentHttp http,
            LlmProperties llm
    ) {
        return new SearchLlmClient(
                http,
                llm
        );
    }

    @Bean
    public SearchMcpClient searchMcpClient(
            SearchAgentHttp http,
            SearchToolTokenCodec tokens,
            SearchAgentProperties properties,
            ObjectMapper mapper
    ) {
        return new SearchMcpClient(
                http,
                tokens,
                properties,
                mapper
        );
    }

    @Bean
    public SearchDocumentRetriever searchDocumentRetriever(
            SearchLlmClient llm,
            SearchMcpClient mcp,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper,
            SearchDocumentQuery query,
            Optional<SearchMeetingHybridRetrieval> hybrid
    ) {
        SearchDocumentRetriever baseline = baselineRetriever(
                llm,
                mcp,
                history,
                access,
                properties,
                mapper,
                query
        );
        if (hybrid.isEmpty()) {
            return baseline;
        }
        SearchMeetingHybridRetrieval retrieval = hybrid.orElseThrow();
        return (
                scope,
                question
        ) -> retrieval.retrieve(
                scope,
                question
        )
                .orElseGet(
                        () -> baseline.retrieve(
                                scope,
                                question
                        )
                );
    }

    private SearchDocumentRetriever baselineRetriever(
            SearchLlmClient llm,
            SearchMcpClient mcp,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper,
            SearchDocumentQuery query
    ) {
        SearchFastPathDocumentRetriever fastPath = new SearchFastPathDocumentRetriever(
                query,
                access,
                new SearchQueryNormalizer()
        );
        if (properties.getRetrievalMode() == SearchRetrievalMode.FAST_PATH) {
            return fastPath;
        }
        return new SearchMcpDocumentRetriever(
                llm,
                mcp,
                history,
                access,
                properties,
                mapper,
                fastPath
        );
    }

    @Bean
    public SearchAnswerGenerator searchAnswerGenerator(
            SearchLlmClient llm,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper,
            Optional<SearchSpringAiModel> springAi,
            SearchAgentHttp http,
            @Value("${knot.search.openai-fallback.enabled:false}") boolean fallbackEnabled,
            @Value("${knot.search.openai-fallback.api-key:}") String fallbackKey,
            @Value("${knot.search.openai-fallback.model}") String fallbackModel
    ) {
        return new SearchStreamingAnswerGenerator(
                llm,
                history,
                access,
                properties,
                mapper,
                springAi,
                openAiFallback(
                        http,
                        fallbackEnabled,
                        fallbackKey,
                        fallbackModel
                )
        );
    }

    private Optional<SearchLlmClient> openAiFallback(
            SearchAgentHttp http,
            boolean enabled,
            String key,
            String model
    ) {
        if (!enabled) {
            return Optional.empty();
        }
        if (key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0 || model.isBlank()) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        LlmProperties fallback = new LlmProperties();
        fallback.setProvider("openai");
        fallback.setBaseUrl(URI.create("https://api.openai.com"));
        fallback.setApiToken(key);
        fallback.setModel(model);
        return Optional.of(
                new SearchLlmClient(
                        http,
                        fallback
                )
        );
    }
}
