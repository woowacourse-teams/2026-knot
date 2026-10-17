package com.knot.backend.testsupport;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@TestConfiguration(proxyBeanMethods = false)
public class SharedPostgresTestConfiguration {

    @Bean
    SmartInitializingSingleton postgresLifecycleProbe(
            ApplicationContext context,
            Environment environment
    ) {
        return () -> SharedPostgresTestSupport.reportContext(
                context.getId(),
                System.identityHashCode(context),
                environment.getProperty("spring.datasource.url")
        );
    }
}
