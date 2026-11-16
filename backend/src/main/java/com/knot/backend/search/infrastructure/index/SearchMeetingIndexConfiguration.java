package com.knot.backend.search.infrastructure.index;

import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "knot.search.index", name = "enabled", havingValue = "true")
@EnableScheduling
class SearchMeetingIndexConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient searchEmbeddingHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Bean
    SearchMeetingDocumentIndex searchMeetingDocumentIndex(
            @Qualifier("searchEmbeddingHttpClient") HttpClient searchEmbeddingHttpClient,
            ObjectMapper mapper,
            @Value("${knot.search.index.directory}") Path directory,
            @Value("${knot.search.index.embedding-url}") URI endpoint,
            @Value("${knot.search.index.embedding-model}") String model,
            @Value("${knot.search.index.embedding-revision}") String revision,
            @Value("${knot.search.index.embedding-token:}") String token
    ) {
        SearchE5EmbeddingClient embeddings = new SearchE5EmbeddingClient(
                searchEmbeddingHttpClient,
                mapper,
                endpoint,
                model,
                token,
                Duration.ofSeconds(10)
        );
        return new SearchMeetingDocumentIndex(
                directory,
                embeddings,
                model + "@" + revision
        );
    }

    @Bean
    SearchMeetingIndexSynchronizer searchMeetingIndexSynchronizer(
            SearchMeetingDocumentIndex index,
            JdbcClient jdbc,
            @Value("${knot.search.index.workspace-ids}") List<Long> workspaceIds
    ) {
        return new SearchMeetingIndexSynchronizer(
                index,
                new SearchMeetingIndexSource(jdbc),
                workspaceIds
        );
    }

    @Bean
    @ConditionalOnProperty(name = {"knot.search.meeting-pipeline.enabled",
            "knot.search.index.query-enabled"}, havingValue = "true")
    SearchMeetingHybridRetrieval searchMeetingHybridRetrieval(
            SearchMeetingDocumentIndex index,
            JdbcClient jdbc,
            SearchDocumentQuery documents,
            SearchToolAccess access,
            @Value("${knot.search.index.workspace-ids}") List<Long> workspaceIds
    ) {
        return new SearchMeetingHybridRetrieval(
                index,
                new SearchMeetingIndexSource(jdbc),
                documents,
                access,
                workspaceIds
        );
    }
}
