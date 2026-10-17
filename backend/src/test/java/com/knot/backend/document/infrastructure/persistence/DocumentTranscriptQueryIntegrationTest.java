package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentTranscriptQuery;
import com.knot.backend.document.application.dto.result.DocumentTranscriptSnapshot;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.recording.infrastructure.persistence.TranscriptSegmentRepositoryAdapter;
import com.knot.backend.testsupport.SharedPostgresDocumentTranscriptTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({SharedPostgresTestConfiguration.class, DocumentTranscriptQueryAdapter.class,
        TranscriptSegmentRepositoryAdapter.class})
class DocumentTranscriptQueryIntegrationTest extends SharedPostgresDocumentTranscriptTestSupport {

    @Autowired
    private DocumentTranscriptQuery query;
    @Autowired
    private TranscriptSegmentRepository segments;
    @Autowired
    private JdbcClient jdbc;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long recordingId;
    private long transcriptId;
    private long documentId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        long author = fixtures.saveMember("녹음자");
        recordingId = fixtures.saveRecording(
                workspaceId,
                author,
                1850999
        );
        transcriptId = fixtures.saveTranscript(recordingId);
        documentId = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                fixtures.saveJob(
                        transcriptId,
                        "SUCCEEDED"
                ),
                "정책"
        );
    }

    @Test
    @DisplayName("최신 원문 대신 문서에 연결된 원문과 그 구간만 반환한다")
    void find_success_sourceAndOrder() {
        // given
        long latest = fixtures.saveTranscript(recordingId);
        segments.saveAll(
                List.of(
                        TranscriptSegment.create(
                                latest,
                                0,
                                0,
                                null,
                                null,
                                "최신 원문 발언"
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
                                null,
                                "두 번째"
                        )
                )
        );

        // when
        DocumentTranscriptSnapshot result = query.find(
                workspaceId,
                documentId
        )
                .orElseThrow();

        // then
        assertThat(result.transcriptId()).isEqualTo(transcriptId);
        assertThat(result.transcriptText()).isEqualTo("전체 녹음 원문");
        assertThat(result.recordingDurationMillis()).isEqualTo(1850999);
        assertThat(result.segments()).extracting(segment -> segment.text())
                .containsExactly(
                        "첫 번째",
                        "두 번째",
                        "세 번째"
                );
    }

    @Test
    @DisplayName("다른 Workspace의 문서는 연결 원문을 반환하지 않는다")
    void find_failure_otherWorkspace() {
        // when & then
        assertThat(
                query.find(
                        fixtures.saveWorkspace(),
                        documentId
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("없는 문서는 연결 원문을 반환하지 않는다")
    void find_failure_missingDocument() {
        // when & then
        assertThat(
                query.find(
                        workspaceId,
                        Long.MAX_VALUE
                )
        ).isEmpty();
    }
}
