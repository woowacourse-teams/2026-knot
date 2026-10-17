package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.query.DocumentConfirmationParameters;
import com.knot.backend.document.application.dto.result.DocumentConfirmationsResult;
import com.knot.backend.document.domain.model.DocumentConfirmationState;
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
class DocumentConfirmationSnapshotIntegrationTest {
    @Autowired
    private DocumentConfirmationService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private DocumentConfirmationQueryAdapter query;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long targetId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("조회자");
        targetId = fixtures.saveMember("확인 대상");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        fixtures.join(
                workspaceId,
                targetId
        );
        long recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                1000
        );
        long transcriptId = fixtures.saveTranscript(recordingId);
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
        fixtures.target(
                documentId,
                targetId,
                null
        );
    }

    @Test
    @DisplayName("집계 후 확인이 커밋되어도 한 응답의 집계와 대상 목록은 같은 스냅샷이다")
    void find_success_concurrentConfirmation() {
        // given
        writeAfterSummary(
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

        // when
        DocumentConfirmationsResult result = find();

        // then
        assertThat(
                result.summary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(
                result.items()
                        .getFirst()
                        .state()
        ).isEqualTo(DocumentConfirmationState.PENDING);
        assertThat(
                result.items()
                        .getFirst()
                        .confirmedAt()
        ).isNull();
        restoreSummary();
        DocumentConfirmationsResult next = find();
        assertThat(
                next.summary()
                        .confirmedCount()
        ).isEqualTo(1);
        assertThat(
                next.items()
                        .getFirst()
                        .state()
        ).isEqualTo(DocumentConfirmationState.CONFIRMED);
    }

    @Test
    @DisplayName("집계 후 탈퇴가 커밋되어도 한 응답의 미확인 집계와 대상 상태는 일치한다")
    void find_success_concurrentDeparture() {
        // given
        writeAfterSummary(
                () -> fixtures.leave(
                        workspaceId,
                        targetId
                )
        );

        // when
        DocumentConfirmationsResult result = find();

        // then
        assertThat(
                result.summary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(
                result.items()
                        .getFirst()
                        .state()
        ).isEqualTo(DocumentConfirmationState.PENDING);
        restoreSummary();
        DocumentConfirmationsResult next = find();
        assertThat(
                next.summary()
                        .excludedCount()
        ).isEqualTo(1);
        assertThat(
                next.items()
                        .getFirst()
                        .state()
        ).isEqualTo(DocumentConfirmationState.EXCLUDED);
    }

    private DocumentConfirmationsResult find() {
        return service.find(
                workspaceId,
                memberId,
                documentId,
                DocumentConfirmationParameters.of(
                        null,
                        null
                )
        );
    }

    private void restoreSummary() {
        doCallRealMethod().when(query)
                .findSummary(
                        workspaceId,
                        documentId,
                        memberId
                );
    }

    private void writeAfterSummary(Runnable write) {
        doAnswer(invocation -> {
            Object summary = invocation.callRealMethod();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> write.run()))
                        .get(
                                10,
                                TimeUnit.SECONDS
                        );
            }
            return summary;
        }).when(query)
                .findSummary(
                        eq(workspaceId),
                        eq(documentId),
                        eq(memberId)
                );
    }
}
