package com.knot.backend.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class SharedPostgresTestSupport {
    private static final Object DATABASE_CREATION_LOCK = new Object();
    private static final Set<String> CREATED_DATABASES = new HashSet<>();
    private static final PostgreSQLContainer POSTGRES = createContainer();

    static {
        POSTGRES.start();
    }

    private SharedPostgresTestSupport() {}

    public static void registerDataSourceProperties(
            DynamicPropertyRegistry registry,
            String databaseGroup
    ) {
        String databaseName = databaseName(databaseGroup);
        ensureDatabaseExists(databaseName);
        registry.add(
                "spring.datasource.url",
                () -> jdbcUrl(databaseName)
        );
        registry.add(
                "spring.datasource.username",
                POSTGRES::getUsername
        );
        registry.add(
                "spring.datasource.password",
                POSTGRES::getPassword
        );
    }

    public static Connection openConnection(String databaseGroup) throws SQLException {
        String databaseName = databaseName(databaseGroup);
        ensureDatabaseExists(databaseName);
        return DriverManager.getConnection(
                jdbcUrl(databaseName),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    public static String containerId() {
        return POSTGRES.getContainerId();
    }

    public static boolean isRunning() {
        return POSTGRES.isRunning();
    }

    public static String databaseName(String databaseGroup) {
        validateDatabaseName(databaseGroup);
        return "knot_test_c3_" + databaseGroup;
    }

    public static void reportContext(
            String contextId,
            int contextIdentity,
            String jdbcUrl
    ) {
        String databaseName = databaseNameFromJdbcUrl(jdbcUrl);
        System.out.printf(
                "C3_POSTGRES context=%s contextIdentity=0x%08x container=%s database=%s%n",
                contextId,
                contextIdentity,
                containerId(),
                databaseName
        );
    }

    private static PostgreSQLContainer createContainer() {
        return new PostgreSQLContainer("pgvector/pgvector:pg18").withDatabaseName("knot_test")
                .withUsername("knot")
                .withPassword("knot");
    }

    private static void ensureDatabaseExists(String databaseName) {
        synchronized (DATABASE_CREATION_LOCK) {
            createDatabaseOnce(databaseName);
        }
    }

    private static void createDatabaseOnce(String databaseName) {
        if (CREATED_DATABASES.contains(databaseName)) {
            return;
        }
        createDatabaseIfMissing(databaseName);
        CREATED_DATABASES.add(databaseName);
    }

    private static void createDatabaseIfMissing(String databaseName) {
        boolean databaseExists = false;
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement
                        .executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + databaseName + "'")) {
            databaseExists = resultSet.next();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not inspect test database " + databaseName,
                    exception
            );
        }
        if (databaseExists) {
            return;
        }
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not create test database " + databaseName,
                    exception
            );
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static String jdbcUrl(String databaseName) {
        String rootUrl = POSTGRES.getJdbcUrl();
        int databaseSeparator = rootUrl.lastIndexOf('/');
        if (databaseSeparator < 0) {
            throw new IllegalStateException("PostgreSQL JDBC URL has no database path");
        }
        return rootUrl.substring(
                0,
                databaseSeparator + 1
        ) + databaseName;
    }

    private static String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "unavailable";
        }
        int databaseSeparator = jdbcUrl.lastIndexOf('/');
        if (databaseSeparator < 0 || databaseSeparator == jdbcUrl.length() - 1) {
            return "unavailable";
        }
        return jdbcUrl.substring(databaseSeparator + 1);
    }

    private static void validateDatabaseName(String databaseName) {
        if (!databaseName.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid test database name: " + databaseName);
        }
    }
}
