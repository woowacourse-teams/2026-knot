package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    private RecordingAudioStorage storage;

    @Test
    @DisplayName("활성 스케줄러는 문서 실패 입력만 만료 정리하고 오래된 오디오는 보존한다")
    void scheduler_successCleansAndDeletesAutomatically() throws Exception {
        // given
        transactions.executeWithoutResult(status -> {
            DocumentFixtures fixtures = new DocumentFixtures(jdbc);
            long member = fixtures.saveMember("녹음자");
            long workspace = fixtures.saveWorkspace();
            long recording = fixtures.saveRecording(
                    workspace,
                    member,
                    120000
            );
            long transcript = fixtures.saveTranscript(recording);
            Instant expiredAudio = Instant.now()
                    .minus(Duration.ofDays(31));
            jdbc.sql("UPDATE transcripts SET created_at = :time WHERE id = :id")
                    .param(
                            "time",
                            Timestamp.from(expiredAudio)
                    )
                    .param(
                            "id",
                            transcript
                    )
                    .update();
            fixtures.saveJob(
                    transcript,
                    "FAILED",
                    expiredAudio,
                    expiredAudio
            );
            jdbc.sql("""
                    INSERT INTO recording_audio_uploads
                        (recording_id, storage_key, content_type, content_length, status, reserved_at, completed_at)
                    VALUES (:id, 'recordings/automatic', 'audio/webm', 100, 'COMPLETED', :time, :time)
                    """)
                    .param(
                            "id",
                            recording
                    )
                    .param(
                            "time",
                            Timestamp.from(expiredAudio)
                    )
                    .update();
        });

        // when
        awaitRetentionCleanup();
        verify(
                storage,
                org.mockito.Mockito.after(500)
                        .never()
        ).deleteStoredObject("recordings/automatic");

        // then
        assertThat(countDeletedUploads()).isZero();
        assertThat(countTranscripts()).isZero();
        assertThat(countGenerationJobs()).isZero();
        assertThat(
                jdbc.sql("SELECT processing_status FROM document_generation_batches")
                        .query(String.class)
                        .single()
        ).isEqualTo("FAILED");
    }

    private void awaitRetentionCleanup() throws InterruptedException {
        // 원문 정리와 오디오 삭제는 서로 다른 실행 주기에 완료될 수 있다.
        long deadline = System.nanoTime() + Duration.ofSeconds(10)
                .toNanos();
        while (System.nanoTime() < deadline) {
            if (isRetentionCleanupComplete()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private boolean isRetentionCleanupComplete() {
        return countDeletedUploads() == 0 && countTranscripts() == 0 && countGenerationJobs() == 0;
    }

    private int countTranscripts() {
        return jdbc.sql("SELECT count(*) FROM transcripts")
                .query(Integer.class)
                .single();
    }

    private int countGenerationJobs() {
        return jdbc.sql("SELECT count(*) FROM document_generation_jobs")
                .query(Integer.class)
                .single();
    }

    private int countDeletedUploads() {
        return jdbc.sql("SELECT count(*) FROM recording_audio_uploads WHERE deleted_at IS NOT NULL")
                .query(Integer.class)
                .single();
    }
}
