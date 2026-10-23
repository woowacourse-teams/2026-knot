package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.DocumentFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class DocumentCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("pgvector/pgvector:pg18");

    @Test
    @DisplayName("V61 자료를 보존해 LEGACY로 전환하고 배정 정보의 NULL 조합을 DB에서도 거절한다")
    void migrate_success_preservesLegacyAndRejectsIncompleteState() throws Exception {
        // given
        flyway("61").migrate();
        try (Connection connection = DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword()
        )) {
            JdbcClient jdbc = JdbcClient.create(
                    new SingleConnectionDataSource(
                            connection,
                            true
                    )
            );
            DocumentFixtures fixtures = new DocumentFixtures(jdbc);
            long member = fixtures.saveMember("녹음자");
            long workspace = fixtures.saveWorkspace();
            long recording = fixtures.saveRecording(
                    workspace,
                    member,
                    1000
            );
            long transcript = fixtures.saveTranscript(recording);
            long job = fixtures.saveJob(
                    transcript,
                    "SUCCEEDED"
            );
            fixtures.saveDocument(
                    workspace,
                    recording,
                    transcript,
                    job,
                    "기존 세부 안건"
            );
            String before = jdbc.sql("SELECT concat(id, '|', topic, '|', title, '|', content) FROM documents")
                    .query(String.class)
                    .single();

            // when
            assertThat(flyway("62").migrate().migrationsExecuted).isEqualTo(1);

            // then
            assertThat(
                    jdbc.sql("SELECT concat(id, '|', topic, '|', title, '|', content) FROM documents")
                            .query(String.class)
                            .single()
            ).isEqualTo(before);
            assertThat(
                    jdbc.sql("SELECT grouping_scheme FROM documents")
                            .query(String.class)
                            .single()
            ).isEqualTo("LEGACY");
            assertThat(
                    jdbc.sql("SELECT count(*) FROM document_categories")
                            .query(Long.class)
                            .single()
            ).isZero();
            assertThat(
                    jdbc.sql("SELECT processing_phase FROM document_generation_jobs")
                            .query(String.class)
                            .single()
            ).isEqualTo("WRITE_BODY");
            assertThatThrownBy(
                    () -> jdbc.sql("UPDATE documents SET grouping_scheme = 'BROAD'")
                            .update()
            ).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_document_grouping");
            assertThatThrownBy(
                    () -> jdbc.sql("UPDATE document_generation_jobs SET category_started_at = now()")
                            .update()
            ).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_generation_category_budget");
            assertThatThrownBy(
                    () -> jdbc.sql("""
                            INSERT INTO prepared_documents
                                (job_id, workspace_id, transcript_id, recording_session_id, title, content,
                                 new_category_name, created_at)
                            VALUES (:job, :workspace, :transcript, :recording, '제목', '본문', '불완전 결정', now())
                            """)
                            .param(
                                    "job",
                                    job
                            )
                            .param(
                                    "workspace",
                                    workspace
                            )
                            .param(
                                    "transcript",
                                    transcript
                            )
                            .param(
                                    "recording",
                                    recording
                            )
                            .update()
            ).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_prepared_decision");
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
