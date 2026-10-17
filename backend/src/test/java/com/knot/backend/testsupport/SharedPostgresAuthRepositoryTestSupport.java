package com.knot.backend.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class SharedPostgresAuthRepositoryTestSupport {

    @DynamicPropertySource
    public static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgresTestSupport.registerDataSourceProperties(
                registry,
                "auth_repository"
        );
    }
}
