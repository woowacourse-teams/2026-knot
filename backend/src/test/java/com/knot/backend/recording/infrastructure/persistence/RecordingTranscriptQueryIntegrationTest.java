package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.RecordingTranscriptQuery;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptResult;
import com.knot.backend.recording.application.dto.result.TranscriptSegmentResult;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTranscriptQueryIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecordingTranscriptQuery recordingTranscriptQuery;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;

    RecordingTranscriptQueryIntegrationTest(
            RecordingTranscriptQuery recordingTranscriptQuery,
            TranscriptSegmentRepository transcriptSegmentRepository,
            JdbcClient jdbcClient
    ) {
        this.recordingTranscriptQuery = recordingTranscriptQuery;
        this.transcriptSegmentRepository = transcriptSegmentRepository;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        memberId = fixture.member("루덴스");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("문서 정리 입력으로 쓴 성공 전사의 전체 원문과 구간을 시작 시각·저장 순서로 준다")
    void find_success_documentSourceTranscript() {
        // given
        long recordingId = saved(0);
        long transcriptId = sourceTranscriptId(
                fixture.batch(
                        recordingId,
                        "TOPICS_REGISTERED",
                        "FAILED"
                )
        );
        long otherTranscriptId = transcript(recordingId);
        transcriptSegmentRepository.saveAll(
                List.of(
                        TranscriptSegment.create(
                                otherTranscriptId,
                                0,
                                0,
                                null,
                                null,
                                "다른 전사 발언"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                2,
                                1200,
                                4600L,
                                2,
                                "세 번째"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                null,
                                null,
                                "첫 번째"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                1,
                                1200,
                                null,
                                1,
                                "두 번째"
                        )
                )
        );

        // when
        RecordingTranscriptResult result = recordingTranscriptQuery.find(recordingId)
                .orElseThrow();

        // then
        assertThat(result.recordingId()).isEqualTo(recordingId);
        assertThat(result.transcriptId()).isEqualTo(transcriptId);
        assertThat(result.transcriptText()).isEqualTo("회원 탈퇴 정책을 정했다.");
        assertThat(result.segments()).containsExactly(
                new TranscriptSegmentResult(
                        0,
                        null,
                        null,
                        "첫 번째"
                ),
                new TranscriptSegmentResult(
                        1200,
                        null,
                        1,
                        "두 번째"
                ),
                new TranscriptSegmentResult(
                        1200,
                        4600L,
                        2,
                        "세 번째"
                )
        );
    }

    @Test
    @DisplayName("전사 전·전사 실패·주제 분류 전·NO_CONTENT 녹음은 보여 줄 전사가 없다")
    void find_fail_noTranscriptToShow() {
        // given
        long beforeTranscription = saved(0);
        long transcriptionFailed = saved(1);
        fixture.transcriptionFailed(transcriptionFailed);
        long waitingClassification = saved(2);
        fixture.batch(
                waitingClassification,
                "WAITING_CLASSIFICATION",
                "QUEUED"
        );
        long noContent = saved(3);
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );

        // when
        List<Optional<RecordingTranscriptResult>> results = List.of(
                beforeTranscription,
                transcriptionFailed,
                waitingClassification,
                noContent
        )
                .stream()
                .map(recordingTranscriptQuery::find)
                .toList();

        // then
        assertThat(results).allMatch(Optional::isEmpty);
    }

    private long saved(int secondsAfterStart) {
        return fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(secondsAfterStart)
        );
    }

    private long sourceTranscriptId(long batchId) {
        return jdbcClient.sql("SELECT transcript_id FROM document_generation_batches WHERE id = :batchId")
                .param(
                        "batchId",
                        batchId
                )
                .query(Long.class)
                .single();
    }

    private long transcript(long recordingId) {
        return jdbcClient.sql("""
                INSERT INTO transcripts (recording_session_id, content, created_at)
                VALUES (:recordingId, '나중에 저장된 다른 전사', :createdAt)
                RETURNING id
                """)
                .param(
                        "recordingId",
                        recordingId
                )
                .param(
                        "createdAt",
                        Timestamp.from(RecordingHistoryFixture.CREATED_AT)
                )
                .query(Long.class)
                .single();
    }
}
