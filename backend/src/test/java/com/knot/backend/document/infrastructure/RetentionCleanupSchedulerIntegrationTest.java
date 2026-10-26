package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.application.RecordingAudioStorage;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.retention.enabled=true", "knot.retention.poll-interval=100ms",
        "knot.retention.deletion-interval=100ms"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RetentionCleanupSchedulerIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private RecordingAudioStorage storage;

    @Test
    @DisplayName("활성 스케줄러는 폐기 녹음만 정리하고 오래된 실패 Job·원문과 내용 없음 녹음의 오디오·과거 삭제 예약은 그대로 둔다")
    void scheduler_successKeepsRecordingResultsAndCleansDiscardedRecording() {
        // given
        jdbc.sql("TRUNCATE TABLE recording_audio_cleanup, members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("녹음자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        Instant longAgo = Instant.now()
                .minus(Duration.ofDays(31));
        long failedRecording = fixtures.saveRecording(
                workspace,
                member,
                120000
        );
        long failedTranscript = fixtures.saveTranscript(failedRecording);
        moveTranscriptCreation(
                failedTranscript,
                longAgo
        );
        long failedJob = fixtures.saveJob(
                failedTranscript,
                "FAILED",
                longAgo,
                longAgo
        );
        long noContentRecording = fixtures.saveRecording(
                workspace,
                member,
                120000
        );
        long noContentTranscript = fixtures.saveTranscript(noContentRecording);
        saveSegment(noContentTranscript);
        markNoContent(fixtures.saveGenerationBatch(noContentTranscript));
        long noContentUpload = saveCompletedUpload(
                noContentRecording,
                longAgo
        );
        long previousDeletionTask = savePendingDeletionTask(
                noContentUpload,
                longAgo
        );
        saveScheduledAllCleanup(
                noContentRecording,
                longAgo
        );
        long discardedRecording = fixtures.saveRecording(
                workspace,
                member,
                120000
        );
        discard(discardedRecording);
        saveDormantCleanup(
                discardedRecording,
                longAgo
        );

        // when
        verify(
                storage,
                timeout(10_000)
        ).deleteRecordingPrefix("recordings/" + discardedRecording + "/");

        // then
        verify(
                storage,
                after(500).never()
        ).deleteRecordingPrefix("recordings/" + noContentRecording + "/");
        assertThat(
                countById(
                        "document_generation_jobs",
                        failedJob
                )
        ).isEqualTo(1);
        assertThat(
                countById(
                        "transcripts",
                        failedTranscript
                )
        ).isEqualTo(1);
        assertThat(
                countById(
                        "transcripts",
                        noContentTranscript
                )
        ).isEqualTo(1);
        assertThat(countSegments(noContentTranscript)).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT status FROM recording_audio_deletion_tasks WHERE id = :id")
                        .param(
                                "id",
                                previousDeletionTask
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("PENDING");
        assertThat(
                jdbc.sql("SELECT count(*) FROM recording_audio_deletion_tasks")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("""
                        SELECT count(*) FROM recording_audio_uploads
                        WHERE id = :id AND deleted_at IS NULL AND revoked_at IS NULL
                        """)
                        .param(
                                "id",
                                noContentUpload
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT last_swept_at FROM recording_audio_cleanup WHERE recording_id = :id")
                        .param(
                                "id",
                                noContentRecording
                        )
                        .query(Timestamp.class)
                        .optional()
        ).isEmpty();
    }

    private void moveTranscriptCreation(
            long transcriptId,
            Instant createdAt
    ) {
        jdbc.sql("UPDATE transcripts SET created_at = :time WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(createdAt)
                )
                .param(
                        "id",
                        transcriptId
                )
                .update();
    }

    private void saveSegment(long transcriptId) {
        jdbc.sql("""
                INSERT INTO transcript_segments (transcript_id, position, start_millis, text)
                VALUES (:id, 0, 0, '내용 없음으로 분류된 발언')
                """)
                .param(
                        "id",
                        transcriptId
                )
                .update();
    }

    private void markNoContent(long batchId) {
        jdbc.sql("""
                UPDATE document_generation_batches SET topic_registration_state = 'NO_CONTENT',
                    processing_status = 'NO_CONTENT', cleanup_requested_at = registered_at, finished_at = registered_at,
                    queued_count = 0, running_count = 0, succeeded_count = 0, failed_count = 0
                WHERE id = :id
                """)
                .param(
                        "id",
                        batchId
                )
                .update();
    }

    private long saveCompletedUpload(
            long recordingId,
            Instant completedAt
    ) {
        return jdbc.sql("""
                INSERT INTO recording_audio_uploads
                    (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                VALUES (:recording, :key, 'audio/webm', 100, 'COMPLETED', :time, :time) RETURNING id
                """)
                .param(
                        "recording",
                        recordingId
                )
                .param(
                        "key",
                        "recordings/" + recordingId + "/audio.webm"
                )
                .param(
                        "time",
                        Timestamp.from(completedAt)
                )
                .query(Long.class)
                .single();
    }

    private long savePendingDeletionTask(
            long uploadId,
            Instant requestedAt
    ) {
        return jdbc
                .sql(
                        """
                                INSERT INTO recording_audio_deletion_tasks (upload_id, storage_key, status, requested_at, next_attempt_at)
                                SELECT id, storage_key, 'PENDING', :at, :at FROM recording_audio_uploads WHERE id = :id
                                RETURNING id
                                """
                )
                .param(
                        "id",
                        uploadId
                )
                .param(
                        "at",
                        Timestamp.from(requestedAt)
                )
                .query(Long.class)
                .single();
    }

    private void saveScheduledAllCleanup(
            long recordingId,
            Instant requestedAt
    ) {
        jdbc.sql("""
                INSERT INTO recording_audio_cleanup (recording_id, storage_prefix, scope, created_at, requested_at,
                    next_attempt_at)
                VALUES (:id, :prefix, 'ALL', :at, :at, :at)
                """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "prefix",
                        "recordings/" + recordingId + "/"
                )
                .param(
                        "at",
                        Timestamp.from(requestedAt)
                )
                .update();
    }

    private void saveDormantCleanup(
            long recordingId,
            Instant createdAt
    ) {
        jdbc.sql("""
                INSERT INTO recording_audio_cleanup (recording_id, storage_prefix, created_at)
                VALUES (:id, :prefix, :at)
                """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "prefix",
                        "recordings/" + recordingId + "/"
                )
                .param(
                        "at",
                        Timestamp.from(createdAt)
                )
                .update();
    }

    private void discard(long recordingId) {
        jdbc.sql("UPDATE recording_sessions SET status = 'DISCARDED' WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();
    }

    private int countById(
            String table,
            long id
    ) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                .param(
                        "id",
                        id
                )
                .query(Integer.class)
                .single();
    }

    private int countSegments(long transcriptId) {
        return jdbc.sql("SELECT count(*) FROM transcript_segments WHERE transcript_id = :id")
                .param(
                        "id",
                        transcriptId
                )
                .query(Integer.class)
                .single();
    }
}
