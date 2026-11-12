package com.knot.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

@Tag("integration")
class IsolatedPostgresInitializerIntegrationTest {

    @Test
    void contextsKeepSeparateDatabasesAndClosingOneDoesNotStopPostgres() throws Exception {
        // given
        try (GenericApplicationContext first = context(); GenericApplicationContext second = context()) {
            String containerId = SharedPostgresTestSupport.containerId();
            try (Connection connection = connection(first); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE isolation_marker (id integer PRIMARY KEY)");
                statement.execute("INSERT INTO isolation_marker VALUES (1)");
            }

            // when
            first.close();

            // then
            assertThat(SharedPostgresTestSupport.containerId()).isEqualTo(containerId);
            assertThat(SharedPostgresTestSupport.isRunning()).isTrue();
            try (Connection connection = connection(second);
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT to_regclass('public.isolation_marker')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
            }
            try (GenericApplicationContext recreated = context()) {
                assertThat(
                        recreated.getEnvironment()
                                .getRequiredProperty("spring.datasource.url")
                ).isNotEqualTo(
                        first.getEnvironment()
                                .getRequiredProperty("spring.datasource.url")
                )
                        .isNotEqualTo(
                                second.getEnvironment()
                                        .getRequiredProperty("spring.datasource.url")
                        );
            }
        }
    }

    private GenericApplicationContext context() {
        GenericApplicationContext context = new GenericApplicationContext();
        new IsolatedPostgresInitializer().initialize(context);
        context.refresh();
        return context;
    }

    private Connection connection(GenericApplicationContext context) throws Exception {
        return DriverManager.getConnection(
                context.getEnvironment()
                        .getRequiredProperty("spring.datasource.url"),
                context.getEnvironment()
                        .getRequiredProperty("spring.datasource.username"),
                context.getEnvironment()
                        .getRequiredProperty("spring.datasource.password")
        );
    }
}
