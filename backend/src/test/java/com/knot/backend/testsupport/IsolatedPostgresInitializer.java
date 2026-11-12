package com.knot.backend.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

public final class IsolatedPostgresInitializer
        implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        // Initializers run once per new context, not once per test class. Cache hits
        // reuse the database; a recreated/dirty context receives a fresh database.
        String group = "context_" + UUID.randomUUID()
                .toString()
                .replace(
                        "-",
                        ""
                );
        Map<String, Object> properties = new LinkedHashMap<>();
        SharedPostgresTestSupport.registerDataSourceProperties(
                (
                        name,
                        value
                ) -> properties.put(
                        name,
                        value.get()
                ),
                group
        );
        // JPA slices must use this database rather than replace it with an embedded
        // one.
        properties.put(
                "spring.test.database.replace",
                "NONE"
        );
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "isolatedTestPostgres",
                                properties
                        )
                );
    }
}
