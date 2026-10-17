package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.query.DocumentListParameters;
import com.knot.backend.document.application.dto.result.DocumentCardResult;
import com.knot.backend.document.application.dto.result.DocumentListResult;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.document.infrastructure.persistence.DocumentConfirmationQueryAdapter;
import com.knot.backend.document.infrastructure.persistence.DocumentListQueryAdapter;
import com.knot.backend.recording.infrastructure.persistence.TranscriptSegmentRepositoryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchConversationListQueryAdapter;
import com.knot.backend.search.infrastructure.persistence.SearchMessageListQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
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
class DocumentListSnapshotIntegrationTest {
    @Autowired
    private DocumentListService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private DocumentListQueryAdapter query;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("조회자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        documentId = saveDocument();
        fixtures.target(
                documentId,
                memberId,
                null
        );
    }

    @Test
    @DisplayName("주제 집계 후 문서가 생성되어도 한 응답은 같은 DB 스냅샷으로 읽는다")
    void find_success_concurrentGeneration() {
        writeAfterTopics(this::saveDocument);

        DocumentListResult result = service.find(
                workspaceId,
                memberId,
                DocumentListParameters.of(
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(
                result.topics()
                        .getFirst()
                        .documentCount()
        ).isEqualTo(1);
        assertThat(result.items()).extracting(DocumentCardResult::id)
                .containsExactly(documentId);
        assertThat(
                jdbc.sql("SELECT count(*) FROM documents")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
        doCallRealMethod().when(query)
                .findTopics(
                        eq(workspaceId),
                        eq(memberId),
                        any(DocumentListParameters.class)
                );
        assertThat(
                service.find(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                null,
                                null,
                                null
                        )
                )
                        .items()
        ).hasSize(2);
    }

    @Test
    @DisplayName("주제 집계 후 확인이 완료되어도 필터·카드·집계는 같은 DB 스냅샷으로 읽는다")
    void find_success_concurrentConfirmation() {
        writeAfterTopics(
                () -> jdbc.sql("UPDATE document_confirmations SET confirmed_at = :time WHERE document_id = :id")
                        .param(
                                "time",
                                Timestamp.from(DocumentFixtures.CREATED_AT)
                        )
                        .param(
                                "id",
                                documentId
                        )
                        .update()
        );

        DocumentListResult result = service.find(
                workspaceId,
                memberId,
                DocumentListParameters.of(
                        null,
                        null,
                        MyConfirmationState.PENDING,
                        null
                )
        );

        assertThat(
                result.topics()
                        .getFirst()
                        .documentCount()
        ).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(
                result.items()
                        .getFirst()
                        .myConfirmationState()
        ).isEqualTo(MyConfirmationState.PENDING);
        assertThat(
                result.items()
                        .getFirst()
                        .confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT confirmed_at IS NOT NULL FROM document_confirmations WHERE document_id = :id")
                        .param(
                                "id",
                                documentId
                        )
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        doCallRealMethod().when(query)
                .findTopics(
                        eq(workspaceId),
                        eq(memberId),
                        any(DocumentListParameters.class)
                );
        DocumentListResult next = service.find(
                workspaceId,
                memberId,
                DocumentListParameters.of(
                        null,
                        null,
                        MyConfirmationState.PENDING,
                        null
                )
        );
        assertThat(next.topics()).isEmpty();
        assertThat(next.items()).isEmpty();
    }

    private void writeAfterTopics(Runnable write) {
        doAnswer(invocation -> {
            Object topics = invocation.callRealMethod();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> write.run()))
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
            return topics;
        }).when(query)
                .findTopics(
                        eq(workspaceId),
                        eq(memberId),
                        any(DocumentListParameters.class)
                );
    }

    private long saveDocument() {
        long recording = fixtures.saveRecording(
                workspaceId,
                memberId,
                1850000
        );
        long transcript = fixtures.saveTranscript(recording);
        return fixtures.saveDocument(
                workspaceId,
                recording,
                transcript,
                fixtures.saveJob(
                        transcript,
                        "SUCCEEDED"
                ),
                "정책"
        );
    }
}
