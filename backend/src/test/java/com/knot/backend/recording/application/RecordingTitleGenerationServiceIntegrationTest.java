package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.RecordingTitleTask;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTitleGenerationServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecordingTitleGenerationService recordingTitleGenerationService;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private Clock clock;
    private long workspaceId;
    private long memberId;

    RecordingTitleGenerationServiceIntegrationTest(
            RecordingTitleGenerationService recordingTitleGenerationService,
            JdbcClient jdbcClient
    ) {
        this.recordingTitleGenerationService = recordingTitleGenerationService;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        fixture.clear();
        fixture.activateTitles(RecordingHistoryFixture.CREATED_AT);
        memberId = fixture.member("루덴스");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("NO_CONTENT가 아님이 정해진 성공 전사 녹음만 제목 생성을 접수한다")
    void registerEligible_success_onlyTopicsRegistered() {
        // given
        long registered = recordingWithBatch(
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        recordingWithBatch(
                "NO_CONTENT",
                "NO_CONTENT"
        );
        recordingWithBatch(
                "WAITING_CLASSIFICATION",
                "RUNNING"
        );
        long transcriptionFailed = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        fixture.transcriptionFailed(transcriptionFailed);

        // when
        recordingTitleGenerationService.registerEligible();

        // then
        assertThat(registeredRecordingIds()).containsExactly(registered);
    }

    @Test
    @DisplayName("같은 녹음을 여러 번 확인해도 제목 생성 작업은 하나만 만든다")
    void registerEligible_success_noDuplicate() {
        // given
        recordingWithBatch(
                "TOPICS_REGISTERED",
                "FAILED"
        );
        recordingTitleGenerationService.registerEligible();

        // when
        recordingTitleGenerationService.registerEligible();

        // then
        assertThat(registeredRecordingIds()).hasSize(1);
    }

    @Test
    @DisplayName("선점한 작업은 문서 정리에 쓴 전사를 넘기고 임대 시간 안에는 다시 선점되지 않는다")
    void claim_success_onceWithinLease() {
        // given
        long recordingId = registeredRecording();

        // when
        Optional<RecordingTitleTask> first = recordingTitleGenerationService.claim(recordingId);
        Optional<RecordingTitleTask> second = recordingTitleGenerationService.claim(recordingId);

        // then
        assertThat(first).hasValueSatisfying(task -> {
            assertThat(task.recordingId()).isEqualTo(recordingId);
            assertThat(task.attempt()).isEqualTo(1);
            assertThat(task.transcript()).isEqualTo("회원 탈퇴 정책을 정했다.");
        });
        assertThat(second).isEmpty();
        assertThat(recordingTitleGenerationService.findRunnable()).isEmpty();
    }

    @Test
    @DisplayName("생성한 제목을 정리해 저장한다")
    void succeed_success() {
        // given
        long recordingId = registeredRecording();
        RecordingTitleTask task = recordingTitleGenerationService.claim(recordingId)
                .orElseThrow();

        // when
        recordingTitleGenerationService.succeed(
                task,
                " 회원 탈퇴 정책 논의 "
        );

        // then
        assertThat(storedGeneration(recordingId)).containsEntry(
                "status",
                "SUCCEEDED"
        )
                .containsEntry(
                        "title",
                        "회원 탈퇴 정책 논의"
                );
    }

    @Test
    @DisplayName("시도를 모두 실패하면 제목 없이 끝내고 녹음·문서 정리 상태는 그대로 둔다")
    void fail_success_exhaustedKeepsRecordingProcessing() {
        // given
        long recordingId = registeredRecording();
        Map<String, Object> recordingBefore = storedRow(
                "recording_sessions",
                "id",
                recordingId
        );
        Map<String, Object> batchBefore = storedRow(
                "document_generation_batches",
                "recording_session_id",
                recordingId
        );

        // when
        for (int attempt = 0; attempt < 3; attempt++) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(60L * attempt));
            RecordingTitleTask task = recordingTitleGenerationService.claim(recordingId)
                    .orElseThrow();
            recordingTitleGenerationService.fail(
                    task,
                    "LLM_UNAVAILABLE"
            );
        }

        // then
        assertThat(storedGeneration(recordingId)).containsEntry(
                "status",
                "FAILED"
        )
                .containsEntry(
                        "attempt_count",
                        3
                );
        assertThat(storedGeneration(recordingId).get("title")).isNull();
        assertThat(
                storedRow(
                        "recording_sessions",
                        "id",
                        recordingId
                )
        ).isEqualTo(recordingBefore);
        assertThat(
                storedRow(
                        "document_generation_batches",
                        "recording_session_id",
                        recordingId
                )
        ).isEqualTo(batchBefore);
    }

    private long registeredRecording() {
        long recordingId = recordingWithBatch(
                "TOPICS_REGISTERED",
                "RUNNING"
        );
        recordingTitleGenerationService.registerEligible();
        return recordingId;
    }

    private long recordingWithBatch(
            String registrationState,
            String processingStatus
    ) {
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );
        fixture.batch(
                recordingId,
                registrationState,
                processingStatus
        );
        return recordingId;
    }

    private List<Long> registeredRecordingIds() {
        return jdbcClient.sql("SELECT recording_id FROM recording_title_generations ORDER BY recording_id")
                .query(Long.class)
                .list();
    }

    private Map<String, Object> storedGeneration(long recordingId) {
        return storedRow(
                "recording_title_generations",
                "recording_id",
                recordingId
        );
    }

    private Map<String, Object> storedRow(
            String table,
            String column,
            long id
    ) {
        return jdbcClient.sql("SELECT * FROM " + table + " WHERE " + column + " = :id")
                .param(
                        "id",
                        id
                )
                .query()
                .singleRow();
    }
}
