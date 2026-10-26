package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTitleWorkerIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecordingTitleGenerationService recordingTitleGenerationService;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private long recordingId;

    RecordingTitleWorkerIntegrationTest(
            RecordingTitleGenerationService recordingTitleGenerationService,
            JdbcClient jdbcClient
    ) {
        this.recordingTitleGenerationService = recordingTitleGenerationService;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        fixture.activateTitles(RecordingHistoryFixture.CREATED_AT);
        long memberId = fixture.member("루덴스");
        long workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
        recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        long batchId = fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "FAILED"
        );
        fixture.failedJob(batchId);
    }

    @Test
    @DisplayName("성공 전사가 저장된 녹음은 작업이 돌면 AI 제목을 저장한다")
    void runOnce_success_savesTitle() {
        // given
        RecordingTitleWorker worker = new RecordingTitleWorker(
                recordingTitleGenerationService,
                transcript -> "회원 탈퇴 정책 논의"
        );

        // when
        worker.runOnce();
        worker.shutdown();

        // then
        assertThat(storedTitle()).containsEntry(
                "status",
                "SUCCEEDED"
        )
                .containsEntry(
                        "title",
                        "회원 탈퇴 정책 논의"
                );
    }

    @Test
    @DisplayName("제목 생성 외부 호출이 실패해도 녹음·전사·문서 정리 상태는 바뀌지 않고 제목만 다시 시도를 기다린다")
    void runOnce_fail_externalCallKeepsProcessingState() {
        // given
        List<Map<String, Object>> before = processingState();
        RecordingTitleWorker worker = new RecordingTitleWorker(
                recordingTitleGenerationService,
                transcript -> {
                    throw new LlmException(LlmErrorCode.LLM_TIMEOUT);
                }
        );

        // when
        worker.runOnce();
        worker.shutdown();

        // then
        assertThat(processingState()).isEqualTo(before);
        assertThat(storedTitle()).containsEntry(
                "status",
                "QUEUED"
        )
                .containsEntry(
                        "failure_code",
                        "LLM_TIMEOUT"
                )
                .containsEntry(
                        "attempt_count",
                        1
                );
        assertThat(storedTitle().get("title")).isNull();
    }

    private Map<String, Object> storedTitle() {
        return jdbcClient.sql("SELECT * FROM recording_title_generations WHERE recording_id = :id")
                .param(
                        "id",
                        recordingId
                )
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> processingState() {
        return List.of(
                row("SELECT * FROM recording_sessions WHERE id = :id"),
                row("SELECT * FROM recording_audio_uploads WHERE recording_id = :id"),
                row("SELECT * FROM document_generation_batches WHERE recording_session_id = :id"),
                row("""
                        SELECT j.* FROM document_generation_jobs j
                        JOIN document_generation_batches b ON b.id = j.batch_id
                        WHERE b.recording_session_id = :id
                        """)
        );
    }

    private Map<String, Object> row(String sql) {
        return jdbcClient.sql(sql)
                .param(
                        "id",
                        recordingId
                )
                .query()
                .singleRow();
    }
}
