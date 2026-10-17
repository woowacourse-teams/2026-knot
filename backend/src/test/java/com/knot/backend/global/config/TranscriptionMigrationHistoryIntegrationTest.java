package com.knot.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class TranscriptionMigrationHistoryIntegrationTest {
    private static final String CURRENT_LOCATION = "classpath:db/migration";
    private static final String QUEUE_LOCATION = "classpath:db/transcription-queue-history";
    private static final String TRANSCRIPTION_HISTORY_VERSION = "51";

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withDatabaseName("transcription_history")
            .withUsername("knot")
            .withPassword("knot");

    @BeforeEach
    void clearSchema() {
        configuration(
                CURRENT_LOCATION,
                "latest"
        ).cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    @DisplayName("기존 대기열 DB를 repair하고 전사·문서·탐색 데이터를 보존하며 업그레이드한다")
    void migrate_success_preservesQueueHistoryAndData() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        execute("""
                INSERT INTO recording_transcriptions
                    (recording_session_id, attempt_id, status, started_at, finished_at, queued_at, failure_code)
                VALUES
                    (1, gen_random_uuid(), 'SUCCEEDED', '2026-10-01T00:00:00Z', '2026-10-01T00:00:20Z',
                        '2026-10-01T00:00:00Z', NULL),
                    (2, gen_random_uuid(), 'FAILED', '2026-10-01T00:01:00Z', '2026-10-01T00:01:20Z',
                        '2026-10-01T00:01:00Z', 'AI_FAILED'),
                    (3, gen_random_uuid(), 'QUEUED', NULL, NULL, '2026-10-01T00:02:00Z', NULL);
                """);
        List<String> before = retainedRows();
        List<String> historyBefore = query("""
                SELECT (to_jsonb(h) - 'checksum' - 'description')::text
                FROM flyway_schema_history h ORDER BY installed_rank
                """);

        // when
        Flyway upgraded = currentFlyway();
        upgraded.repair();
        upgraded.migrate();

        // then
        assertThat(retainedRows()).isEqualTo(before);
        assertThat(query("""
                SELECT (to_jsonb(h) - 'checksum' - 'description')::text
                FROM flyway_schema_history h WHERE version <> '51' ORDER BY installed_rank
                """)).isEqualTo(historyBefore);
        assertThat(
                query("SELECT checksum::text FROM flyway_schema_history WHERE version IN ('49', '50') ORDER BY version")
        ).containsExactly(
                "-272133924",
                "-835008331"
        );
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(query("SELECT version FROM flyway_schema_history ORDER BY installed_rank")).endsWith("51");
        assertThat(query("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'recording_audio_uploads' AND column_name = 'storage_version_id'
                """)).containsExactly("storage_version_id");
        execute("""
                UPDATE recording_transcriptions
                SET status = 'PROCESSING', failure_code = NULL, started_at = '2026-10-02T00:00:00Z'
                WHERE recording_session_id = 2;
                UPDATE recording_transcriptions SET status = 'SUCCEEDED' WHERE recording_session_id = 2;
                """);
        upgraded.migrate();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("현재 저장소 V50 DB의 기존 데이터와 체크섬을 보존한다")
    void migrate_success_preservesCurrentHistoryAndData() throws SQLException {
        // given
        configuration(
                CURRENT_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        execute("""
                INSERT INTO recording_transcriptions (recording_session_id, attempt_id, status, started_at)
                VALUES (1, gen_random_uuid(), 'PROCESSING', '2026-10-01T00:00:00Z');
                UPDATE recording_audio_uploads SET storage_version_id = 'existing-version' WHERE recording_id = 1;
                """);
        List<String> historyBefore = historyRows();
        List<String> before = retainedRows();

        // when
        Flyway upgraded = currentFlyway();
        upgraded.migrate();

        // then
        assertThat(historyRows()).isEqualTo(historyBefore);
        assertThat(retainedRows()).isEqualTo(before);
        assertThat(query("SELECT storage_version_id FROM recording_audio_uploads WHERE recording_id = 1"))
                .containsExactly("existing-version");
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("빈 DB는 현재 저장소 이력으로 설치하고 V51까지 검증한다")
    void migrate_success_freshDatabase() throws SQLException {
        // given
        Flyway fresh = currentFlyway();

        // when
        fresh.migrate();

        // then
        assertThat(fresh.validateWithResult().validationSuccessful).isTrue();
        assertThat(query("SELECT checksum::text FROM flyway_schema_history WHERE version = '49'"))
                .containsExactly("-272133924");
        assertThat(query("SELECT checksum::text FROM flyway_schema_history WHERE version = '50'"))
                .containsExactly("-835008331");
        assertThat(query("SELECT version FROM flyway_schema_history ORDER BY installed_rank")).endsWith("51");
    }

    @Test
    @DisplayName("repair만 실행하면 기존 데이터는 보존하지만 빠진 스토리지 컬럼은 생성되지 않는다")
    void repair_success_preservesDataWithoutApplyingSchemaChanges() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        List<String> before = retainedRows();

        // when
        Flyway upgraded = currentFlyway();
        upgraded.repair();

        // then
        assertThat(
                configuration(
                        CURRENT_LOCATION,
                        "50"
                ).load()
                        .validateWithResult().validationSuccessful
        ).isTrue();
        assertThat(retainedRows()).isEqualTo(before);
        assertThat(query("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'recording_audio_uploads' AND column_name = 'storage_version_id'
                """)).isEmpty();
        assertThat(query("SELECT version FROM flyway_schema_history WHERE version = '51'")).isEmpty();
    }

    @Test
    @DisplayName("확인되지 않은 체크섬 불일치는 복구하지 않고 기존 이력을 유지하며 실패한다")
    void migrate_failure_unknownChecksum() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        execute("UPDATE flyway_schema_history SET checksum = 12345 WHERE version = '50'");
        List<String> before = historyRows();

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> currentFlyway().migrate());
        // then
        assertThat(failure).isInstanceOf(FlywayException.class);
        assertThat(historyRows()).isEqualTo(before);
        assertThat(query("SELECT version FROM flyway_schema_history WHERE version = '51'")).isEmpty();
    }

    @Test
    @DisplayName("알려진 대기열 이력이어도 다른 버전의 체크섬 오류는 검증에서 차단한다")
    void migrate_failure_unrelatedChecksumMismatch() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        execute("UPDATE flyway_schema_history SET checksum = 12345 WHERE version = '48'");
        List<String> before = historyRows();

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> currentFlyway().migrate());
        // then
        assertThat(failure).isInstanceOf(FlywayException.class);
        assertThat(historyRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("복구 후 실패 원인 없는 FAILED 전사와 미완료 업로드의 버전 저장을 거부한다")
    void write_failure_invalidTranscriptionAndStorageVersion() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        Flyway upgraded = currentFlyway();
        upgraded.repair();
        upgraded.migrate();

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> execute("""
                INSERT INTO recording_transcriptions (recording_session_id, attempt_id, status, started_at)
                VALUES (1, gen_random_uuid(), 'FAILED', CURRENT_TIMESTAMP)
                """));
        // then
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, storage_version_id)
                VALUES (2, 'invalid-version', 'audio/webm', 10, 'RESERVED', CURRENT_TIMESTAMP, 'version')
                """)).isInstanceOf(SQLException.class);
        execute("UPDATE recording_audio_uploads SET storage_version_id = 'retained-version' WHERE recording_id = 1");
        assertThat(query("SELECT storage_version_id FROM recording_audio_uploads WHERE recording_id = 1"))
                .containsExactly("retained-version");
    }

    @Test
    @DisplayName("V51 제약조건 적용 실패 시 새 버전 이력과 DDL을 롤백하고 기존 데이터를 보존한다")
    void migrate_failure_rollsBackInvalidStorageVersion() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        execute("""
                ALTER TABLE recording_audio_uploads ADD COLUMN storage_version_id TEXT;
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, storage_version_id)
                VALUES (2, 'invalid-version', 'audio/webm', 10, 'RESERVED', CURRENT_TIMESTAMP, 'version');
                """);
        List<String> before = retainedRows();
        Flyway upgraded = currentFlyway();
        upgraded.repair();

        // when
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(upgraded::migrate);
        // then
        assertThat(failure).isInstanceOf(FlywayException.class);
        assertThat(retainedRows()).isEqualTo(before);
        assertThat(query("SELECT version FROM flyway_schema_history WHERE version = '51'")).isEmpty();
        assertThat(query("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'recording_transcriptions'::regclass
                    AND conname = 'chk_recording_transcriptions_time'
                """)).containsExactly("chk_recording_transcriptions_time");
    }

    @Test
    @DisplayName("V53 표준 DB의 전사와 객체 version을 보존하며 V56 대기열과 소유권을 추가한다")
    void migrate_success_standardQueueOwnership() throws SQLException {
        // given
        configuration(
                CURRENT_LOCATION,
                "53"
        ).load()
                .migrate();
        insertRetainedData();
        execute("""
                UPDATE recording_audio_uploads SET storage_version_id = 'version-before' WHERE recording_id = 1;
                INSERT INTO recording_transcriptions(recording_session_id, attempt_id, status, started_at, failure_code)
                VALUES (1, '00000000-0000-0000-0000-000000000001', 'PROCESSING', '2026-10-01T00:00:00Z', NULL),
                       (2, '00000000-0000-0000-0000-000000000002', 'FAILED', '2026-10-01T00:00:00Z', 'FAILED'),
                       (3, '00000000-0000-0000-0000-000000000003', 'SUCCEEDED', '2026-10-01T00:00:00Z', NULL);
                """);
        List<String> before = query("SELECT attempt_id::text || status FROM recording_transcriptions ORDER BY id");
        // when
        configuration(
                CURRENT_LOCATION,
                "56"
        ).load()
                .migrate();
        // then
        assertThat(query("SELECT attempt_id::text || status FROM recording_transcriptions ORDER BY id"))
                .isEqualTo(before);
        assertThat(query("SELECT source_version_id FROM recording_transcriptions WHERE recording_session_id = 1"))
                .containsExactly("version-before");
        assertThat(query("SELECT claim_epoch::text FROM recording_transcriptions ORDER BY id")).containsExactly(
                "0",
                "0",
                "0"
        );
        assertThat(
                configuration(
                        CURRENT_LOCATION,
                        "56"
                ).load()
                        .validateWithResult().validationSuccessful
        ).isTrue();
    }

    @Test
    @DisplayName("과거 수동 QUEUED DB도 기존 상태와 시도를 보존하며 V56으로 업그레이드한다")
    void migrate_success_historicalQueueOwnership() throws SQLException {
        // given
        configuration(
                QUEUE_LOCATION,
                "50"
        ).load()
                .migrate();
        insertRetainedData();
        execute("""
                INSERT INTO recording_transcriptions
                    (recording_session_id, attempt_id, status, queued_at, started_at, finished_at)
                VALUES (1, '00000000-0000-0000-0000-000000000001', 'PROCESSING',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL),
                       (2, '00000000-0000-0000-0000-000000000002', 'QUEUED', CURRENT_TIMESTAMP, NULL, NULL),
                       (3, '00000000-0000-0000-0000-000000000003', 'SUCCEEDED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                """);
        Flyway upgraded = configuration(
                CURRENT_LOCATION,
                "56"
        ).load();
        List<String> before = query("SELECT attempt_id::text || status FROM recording_transcriptions ORDER BY id");
        // when
        upgraded.repair();
        upgraded.migrate();
        // then
        assertThat(query("SELECT attempt_id::text || status FROM recording_transcriptions ORDER BY id"))
                .isEqualTo(before);
        assertThat(query("SELECT remote_observation FROM recording_transcriptions WHERE status = 'PROCESSING'"))
                .containsExactly("UNCONFIRMED");
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    private Flyway currentFlyway() {
        FluentConfiguration configuration = configuration(
                CURRENT_LOCATION,
                TRANSCRIPTION_HISTORY_VERSION
        );
        return configuration.load();
    }

    private FluentConfiguration configuration(
            String location,
            String target
    ) {
        return Flyway.configure()
                .dataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword()
                )
                .locations(location)
                .target(target);
    }

    private void insertRetainedData() throws SQLException {
        execute("""
                INSERT INTO members (id, nickname) OVERRIDING SYSTEM VALUE VALUES (1, 'retained');
                INSERT INTO workspaces (id, name, created_at) OVERRIDING SYSTEM VALUE
                VALUES (1, 'retained-workspace', '2026-10-01T00:00:00Z');
                INSERT INTO recording_sessions
                    (id, workspace_id, member_id, request_id, tab_id, control_token_hash, status,
                        started_at, ended_at, last_seen_at)
                OVERRIDING SYSTEM VALUE
                SELECT n, 1, 1, gen_random_uuid(), gen_random_uuid(), repeat('a', 64), 'ENDED',
                    '2026-10-01T00:00:00Z'::timestamptz, '2026-10-01T00:00:20Z'::timestamptz,
                    '2026-10-01T00:00:20Z'::timestamptz
                FROM generate_series(1, 3) AS n;
                INSERT INTO search_conversations (id, workspace_id, member_id, created_at, updated_at)
                OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                INSERT INTO search_messages (conversation_id, role, sequence, content, status)
                VALUES (1, 'USER', 1, '반갑다', 'RECEIVED'), (1, 'ASSISTANT', 2, '안녕하세요', 'COMPLETED');
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                VALUES (1, 'retained-audio', 'audio/webm', 10, 'COMPLETED',
                    '2026-10-01T00:00:00Z', '2026-10-01T00:00:20Z');
                INSERT INTO transcripts (id, recording_session_id, content, created_at)
                OVERRIDING SYSTEM VALUE VALUES (1, 1, '보존할 녹음 원문', '2026-10-01T00:00:20Z');
                INSERT INTO document_generation_batches
                    (id, recording_session_id, transcript_id, topic_registration_state, accepted_at, registered_at,
                        processing_status, succeeded_count, finished_at)
                OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 'TOPICS_REGISTERED', '2026-10-01T00:00:20Z',
                    '2026-10-01T00:00:30Z', 'SUCCEEDED', 1, '2026-10-01T00:00:40Z');
                INSERT INTO document_generation_batch_topics (batch_id, position, topic)
                VALUES (1, 0, 'retained-topic');
                INSERT INTO document_generation_jobs
                    (id, transcript_id, batch_id, stage, topic, status,
                        created_at, updated_at)
                OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 'GENERATION', 'retained-topic', 'SUCCEEDED',
                    '2026-10-01T00:00:30Z', '2026-10-01T00:00:40Z');
                INSERT INTO documents
                    (workspace_id, recording_session_id, source_transcript_id, document_generation_job_id,
                        topic, title, content, status, created_at)
                VALUES (1, 1, 1, 1, 'retained-topic', '보존할 문서 제목', '보존할 문서 본문', 'DRAFT',
                    '2026-10-01T00:00:40Z');
                """);
    }

    private List<String> retainedRows() throws SQLException {
        return query("""
                SELECT 'member:' || row_to_json(m)::text FROM members m
                UNION ALL SELECT 'workspace:' || row_to_json(w)::text FROM workspaces w
                UNION ALL SELECT 'session:' || row_to_json(r)::text FROM recording_sessions r
                UNION ALL SELECT 'transcription:' || row_to_json(t)::text FROM recording_transcriptions t
                UNION ALL SELECT 'conversation:' || row_to_json(c)::text FROM search_conversations c
                UNION ALL SELECT 'message:' || row_to_json(m)::text FROM search_messages m
                UNION ALL SELECT 'audio:' || (to_jsonb(a) - 'storage_version_id')::text
                    FROM recording_audio_uploads a
                UNION ALL SELECT 'transcript:' || row_to_json(t)::text FROM transcripts t
                UNION ALL SELECT 'batch:' || row_to_json(b)::text FROM document_generation_batches b
                UNION ALL SELECT 'job:' || row_to_json(j)::text FROM document_generation_jobs j
                UNION ALL SELECT 'document:' || row_to_json(d)::text FROM documents d
                ORDER BY 1
                """);
    }

    private List<String> historyRows() throws SQLException {
        return query(
                "SELECT row_to_json(h)::text FROM flyway_schema_history h "
                        + "WHERE version IN ('49', '50') ORDER BY version"
        );
    }

    private List<String> query(String sql) throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword()
        );
    }
}
