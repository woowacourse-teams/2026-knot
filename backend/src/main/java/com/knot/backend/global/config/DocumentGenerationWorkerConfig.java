package com.knot.backend.global.config;

import com.knot.backend.document.domain.DocumentGenerationRetryPolicy;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DocumentGenerationWorkerProperties.class, LlmProperties.class,
        DocumentCategoryProperties.class})
public class DocumentGenerationWorkerConfig {

    @Bean
    public DocumentGenerationRetryPolicy documentGenerationRetryPolicy() {
        return new DocumentGenerationRetryPolicy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "knot.document-generation.worker", name = "enabled", havingValue = "true")
    public ThreadPoolTaskExecutor documentGenerationExecutor(
            DocumentGenerationWorkerProperties properties,
            LlmProperties llm
    ) {
        properties.validate(llm);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-generation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
