package com.knot.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.lifecycle.Startable;

@Tag("integration")
class SharedPostgresTestSupportIntegrationTest {

    @Test
    @DisplayName("컨텍스트 종료 뒤에도 PostgreSQL이 유지되고 컨텍스트 간 데이터가 격리된다")
    void serverSurvivesContextClosure_andEnforcesDatabaseIsolation() throws SQLException {
        // given
        String originalContainerId = SharedPostgresTestSupport.containerId();

        // when
        try (ConfigurableApplicationContext firstContext = startBootContext()) {
            assertNoStartableContainerBeans(firstContext);
        }

        // then
        assertThat(SharedPostgresTestSupport.isRunning()).isTrue();

        // when
        try (ConfigurableApplicationContext secondContext = startBootContext()) {
            assertNoStartableContainerBeans(secondContext);
        }

        // then
        assertThat(SharedPostgresTestSupport.containerId()).isEqualTo(originalContainerId);
        assertThat(SharedPostgresTestSupport.isRunning()).isTrue();

        try (Connection authConnection = SharedPostgresTestSupport.openConnection("auth_repository");
                Connection documentConnection = SharedPostgresTestSupport.openConnection("document_queries")) {
            String authDatabase = query(
                    authConnection,
                    "SELECT current_database()"
            );
            String documentDatabase = query(
                    documentConnection,
                    "SELECT current_database()"
            );
            assertThat(
                    query(
                            authConnection,
                            "SELECT pg_postmaster_start_time()"
                    )
            ).isEqualTo(
                    query(
                            documentConnection,
                            "SELECT pg_postmaster_start_time()"
                    )
            );

            String markerTable = "c3_marker_" + UUID.randomUUID()
                    .toString()
                    .replace(
                            "-",
                            ""
                    );
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(authDatabase)
                    .as("different JPA context groups must use different databases")
                    .isNotEqualTo(documentDatabase);
            try {
                try (Statement statement = authConnection.createStatement()) {
                    statement.execute(
                            "CREATE TABLE public." + markerTable + " (id integer primary key, value text not null)"
                    );
                    statement.execute("INSERT INTO public." + markerTable + " (id, value) VALUES (1, 'visible')");
                }
                String markerValue = queryMarkerValue(
                        documentConnection,
                        markerTable
                );
                boolean markerDataVisible = "visible".equals(markerValue);
                System.out.printf(
                        "C3_ISOLATION sourceDatabase=%s targetDatabase=%s markerQuery=executed "
                                + "markerDataVisible=%s%n",
                        authDatabase,
                        documentDatabase,
                        markerDataVisible
                );
                softly.assertThat(markerDataVisible)
                        .as("marker data from one context group must not be visible in another")
                        .isFalse();
            } finally {
                try (Statement statement = authConnection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS public." + markerTable);
                }
            }
            softly.assertAll();
        }
    }

    @Test
    @DisplayName("반대 방향에서도 컨텍스트 그룹 간 데이터가 격리된다")
    void markerDataIsNotVisibleFromDocumentQueriesToAuthRepository() throws SQLException {
        try (Connection sourceConnection = SharedPostgresTestSupport.openConnection("document_queries");
                Connection targetConnection = SharedPostgresTestSupport.openConnection("auth_repository")) {
            String sourceDatabase = query(
                    sourceConnection,
                    "SELECT current_database()"
            );
            String targetDatabase = query(
                    targetConnection,
                    "SELECT current_database()"
            );
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(sourceDatabase)
                    .as("different JPA context groups must use different databases")
                    .isNotEqualTo(targetDatabase);

            String markerTable = "c3_marker_" + UUID.randomUUID()
                    .toString()
                    .replace(
                            "-",
                            ""
                    );
            try {
                try (Statement statement = sourceConnection.createStatement()) {
                    statement.execute(
                            "CREATE TABLE public." + markerTable + " (id integer primary key, value text not null)"
                    );
                    statement.execute("INSERT INTO public." + markerTable + " (id, value) VALUES (1, 'visible')");
                }
                boolean markerDataVisible = "visible".equals(
                        queryMarkerValue(
                                targetConnection,
                                markerTable
                        )
                );
                System.out.printf(
                        "C3_ISOLATION_REVERSE sourceDatabase=%s targetDatabase=%s markerQuery=executed "
                                + "markerDataVisible=%s%n",
                        sourceDatabase,
                        targetDatabase,
                        markerDataVisible
                );
                softly.assertThat(markerDataVisible)
                        .as("marker data from one context group must not be visible in another")
                        .isFalse();
            } finally {
                try (Statement statement = sourceConnection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS public." + markerTable);
                }
            }
            softly.assertAll();
        }
    }

    private static String query(
            Connection connection,
            String sql
    ) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static ConfigurableApplicationContext startBootContext() {
        SpringApplication application = new SpringApplication(SharedPostgresTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThat(application.getInitializers())
                .anyMatch(TestcontainersLifecycleApplicationContextInitializer.class::isInstance);
        return application.run();
    }

    private static void assertNoStartableContainerBeans(ConfigurableApplicationContext context) {
        assertThat(
                context.getBeanFactory()
                        .getBeanNamesForType(Startable.class)
        ).isEmpty();
    }

    private static String queryMarkerValue(
            Connection connection,
            String tableName
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement
                        .executeQuery("SELECT value FROM public." + tableName + " WHERE id = 1")) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throwUnlessMarkerTableIsMissing(exception);
            return null;
        }
    }

    private static void throwUnlessMarkerTableIsMissing(SQLException exception) throws SQLException {
        if (!"42P01".equals(exception.getSQLState())) {
            throw exception;
        }
    }
}
