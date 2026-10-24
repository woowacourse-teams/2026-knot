package com.knot.backend.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
public class DocumentTopicExecutorConfig {

    @Bean("documentTopicPlanningExecutor")
    public ThreadPoolTaskExecutor planningExecutor(DocumentTopicReorganizationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getParallelism());
        executor.setMaxPoolSize(properties.getParallelism());
        executor.setQueueCapacity(properties.getParallelism());
        executor.setThreadNamePrefix("document-topic-plan-");
        return executor;
    }

    @Bean("documentTopicExecutor")
    public ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-topic-");
        return executor;
    }
}
