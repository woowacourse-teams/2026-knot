package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentTranscriptResult;
import com.knot.backend.document.infrastructure.persistence.DocumentConfirmationQueryAdapter;
import com.knot.backend.document.infrastructure.persistence.DocumentListQueryAdapter;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.infrastructure.persistence.TranscriptSegmentRepositoryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchConversationListQueryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchMessageListQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@MockitoSpyBean(types = {DocumentListQueryAdapter.class, DocumentConfirmationQueryAdapter.class,
        TranscriptSegmentRepositoryAdapter.class, SearchConversationListQueryAdapter.class,
        SearchMessageListQueryAdapter.class})
class DocumentTranscriptSnapshotIntegrationTest {

    @Autowired
    private DocumentTranscriptService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private TranscriptSegmentRepositoryAdapter segments;
    private long memberId;
    private long workspaceId;
    private long transcriptId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("조회자");
        fixtures.join(
                workspaceId,
                memberId
        );
        long recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                1000
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
        segments.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                null,
                                null,
                                "기존 발언"
                        )
                )
        );
    }

    @Test
    @DisplayName("헤더 조회 후 원문과 구간이 변경되어도 한 응답은 같은 DB 스냅샷으로 읽는다")
    void find_success_consistentSnapshot() {
        // given
        doAnswer(invocation -> {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.sql("UPDATE transcripts SET content = '변경된 원문' WHERE id = :id")
                            .param(
                                    "id",
                                    transcriptId
                            )
                            .update();
                    segments.saveAll(
                            List.of(
                                    TranscriptSegment.create(
                                            transcriptId,
                                            1,
                                            1200,
                                            null,
                                            null,
                                            "새 발언"
                                    )
                            )
                    );
                }))
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
            return invocation.callRealMethod();
        }).when(segments)
                .findAllByTranscriptId(transcriptId);

        // when
        DocumentTranscriptResult result = service.find(
                workspaceId,
                memberId,
                documentId
        );

        // then
        assertThat(result.transcriptText()).isEqualTo("전체 녹음 원문");
        assertThat(result.segments()).extracting(segment -> segment.text())
                .containsExactly("기존 발언");
        assertThat(
                jdbc.sql("SELECT content FROM transcripts WHERE id = :id")
                        .param(
                                "id",
                                transcriptId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("변경된 원문");
        assertThat(
                jdbc.sql("SELECT count(*) FROM transcript_segments WHERE transcript_id = :id")
                        .param(
                                "id",
                                transcriptId
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
        doCallRealMethod().when(segments)
                .findAllByTranscriptId(transcriptId);
        DocumentTranscriptResult next = service.find(
                workspaceId,
                memberId,
                documentId
        );
        assertThat(next.transcriptText()).isEqualTo("변경된 원문");
        assertThat(next.segments()).hasSize(2);
    }
}
