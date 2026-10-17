package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class RecordingAudioChunkMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withDatabaseName("audio_chunk_migration")
            .withUsername("knot")
            .withPassword("knot");

    @Test
    @DisplayName("조각 저장 migration은 기존 녹음 데이터를 유지하며 최종 WebM 모드로 채운다")
    void migrate_success_preservesLegacyMode() throws Exception {
        // given
        flyway("53").migrate();
        try (Connection connection = DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO members (id, nickname) OVERRIDING SYSTEM VALUE VALUES (1, 'legacy');
                    INSERT INTO workspaces (id, name, created_at) OVERRIDING SYSTEM VALUE
                        VALUES (1, '기존 팀', '2026-10-17T00:00:00Z');
                    INSERT INTO recording_sessions (
                        workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                        started_at, current_interval_started_at, last_seen_at
                    ) VALUES (1, 1, gen_random_uuid(), gen_random_uuid(), repeat('a', 64), 'RECORDING',
                        '2026-10-17T00:00:00Z', '2026-10-17T00:00:00Z', '2026-10-17T00:00:00Z');
                    """);
        }

        // when
        MigrateResult result = flyway("54").migrate();

        // then
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword()
        ); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT audio_mode, status, member_id FROM recording_sessions
                """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("audio_mode")).isEqualTo("FINAL_WEBM_V1");
            assertThat(rows.getString("status")).isEqualTo("RECORDING");
            assertThat(rows.getLong("member_id")).isEqualTo(1);
            assertThat(rows.next()).isFalse();
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword()
                )
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
