package com.knot.backend.global.config;

import com.knot.backend.global.infrastructure.llm.LmStudioClient;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class LlmConfig {

    @Bean
    public HttpClient llmHttpClient(LlmProperties properties) {
        properties.validateEnabledConfiguration();
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "knot.llm", name = "provider", havingValue = "lm-studio", matchIfMissing = true)
    public LmStudioClient lmStudioClient(
            HttpClient llmHttpClient,
            ObjectMapper mapper,
            LlmProperties properties
    ) {
        return new LmStudioClient(
                llmHttpClient,
                mapper,
                properties
        );
    }
}
