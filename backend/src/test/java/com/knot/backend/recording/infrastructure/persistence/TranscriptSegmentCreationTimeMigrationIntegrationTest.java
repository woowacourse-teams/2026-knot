package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.testsupport.IsolatedPostgresTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@Tag("integration")
@DataJpaTest
@IsolatedPostgresTest
class TranscriptSegmentCreationTimeMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("기존 구간의 생성 시간은 부모 전사본의 생성 시간으로 보정하고 내용은 보존한다")
    void migrate_success_backfillsCreationTimeFromTranscript() throws Exception {
        // given
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE SCHEMA segment_creation_migration;
                    CREATE TABLE segment_creation_migration.transcripts (
                        id BIGINT PRIMARY KEY,
                        created_at TIMESTAMPTZ NOT NULL
                    );
                    CREATE TABLE segment_creation_migration.transcript_segments (
                        id BIGINT PRIMARY KEY,
                        transcript_id BIGINT NOT NULL REFERENCES segment_creation_migration.transcripts(id),
                        text TEXT NOT NULL
                    );
                    INSERT INTO segment_creation_migration.transcripts
                    VALUES (1, '2026-10-01T00:00:00Z'), (2, '2026-10-02T00:00:00Z');
                    INSERT INTO segment_creation_migration.transcript_segments
                    VALUES (10, 1, '첫 번째'), (11, 1, '두 번째'), (12, 2, '다른 전사본');
                    """);
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas("segment_creation_migration")
                        .defaultSchema("segment_creation_migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("52")
                        .target("53")
                        .load();

                // when
                flyway.migrate();

                // then
                try (ResultSet rows = statement.executeQuery("""
                        SELECT id, text, created_at FROM segment_creation_migration.transcript_segments ORDER BY id
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(10);
                    assertThat(rows.getString("text")).isEqualTo("첫 번째");
                    assertThat(
                            rows.getObject(
                                    "created_at",
                                    OffsetDateTime.class
                            )
                                    .toInstant()
                    ).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(11);
                    assertThat(rows.getString("text")).isEqualTo("두 번째");
                    assertThat(
                            rows.getObject(
                                    "created_at",
                                    OffsetDateTime.class
                            )
                                    .toInstant()
                    ).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(12);
                    assertThat(rows.getString("text")).isEqualTo("다른 전사본");
                    assertThat(
                            rows.getObject(
                                    "created_at",
                                    OffsetDateTime.class
                            )
                                    .toInstant()
                    ).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                statement.execute("DROP SCHEMA segment_creation_migration CASCADE");
            }
        }
    }
}
