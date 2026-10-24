package com.knot.backend.search.qa;

import com.knot.backend.search.application.SearchDocumentRetriever;
import com.knot.backend.search.application.SearchDocumentToolService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class DirectSearchLatencyConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "latency.variant", havingValue = "direct-search")
    SearchDocumentRetriever directSearchRetriever(SearchDocumentToolService tools) {
        return new ManualDirectSearchRetriever(tools);
    }
}
