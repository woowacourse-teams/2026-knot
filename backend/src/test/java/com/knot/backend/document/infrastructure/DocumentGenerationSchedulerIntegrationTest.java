package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationIntakeService;
import com.knot.backend.document.application.DocumentGenerationProgressService;
import com.knot.backend.document.application.DocumentGenerationService;
import com.knot.backend.document.application.DocumentTopicClassificationService;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=true",
        "knot.document-generation.worker.poll-interval=100ms",
        "knot.document-generation.worker.recovery-interval=100ms"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentGenerationSchedulerIntegrationTest {

    @Autowired
    private DocumentGenerationIntakeService intake;
    @Autowired
    private DocumentGenerationProgressService progress;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private DocumentTopicClassificationService classifier;
    @MockitoBean
    private DocumentGenerationService generator;

    @Test
    @DisplayName("접수만 하면 실제 스케줄러가 분류와 두 주제 생성을 자동으로 완료한다")
    void dispatchQueued_success_automaticallyCompletesIntake() throws Exception {
        // given
        assertFreshDatabase();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("녹음자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        long recording = fixtures.saveRecording(
                workspace,
                member,
                120000
        );
        long transcript = fixtures.saveTranscript(recording);
        when(classifier.classify(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return DocumentTopicClassificationResult.fromNames(
                    List.of(
                            "A",
                            "B"
                    )
            );
        });
        when(
                generator.generate(
                        anyString(),
                        anyString()
                )
        ).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new DocumentGenerationResult(
                    "제목",
                    "요약",
                    "## 핵심 요약\n내용"
            );
        });
        // when
        transactions.executeWithoutResult(
                status -> intake.acceptCompletedTranscript(
                        workspace,
                        recording,
                        transcript
                )
        );
        awaitCompletion(
                workspace,
                recording
        );
        // then
        assertThat(
                progress.findProgress(
                        workspace,
                        recording
                )
                        .orElseThrow()
                        .succeededCount()
        ).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT count(*) FROM documents")
                        .query(Long.class)
                        .single()
        ).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT count(*) FROM document_confirmations")
                        .query(Long.class)
                        .single()
        ).isEqualTo(2);
        assertThat(
                jdbc.sql("SELECT sum(attempt_count) FROM document_generation_jobs")
                        .query(Long.class)
                        .single()
        ).isEqualTo(3);
    }

    private void assertFreshDatabase() {
        List<String> tables = List.of(
                "members",
                "workspaces",
                "recording_sessions",
                "transcripts",
                "document_generation_batches",
                "document_generation_jobs",
                "documents",
                "document_confirmations"
        );
        for (String table : tables) {
            assertThat(
                    jdbc.sql("SELECT count(*) FROM " + table)
                            .query(Long.class)
                            .single()
            ).as(
                    "전용 테스트 DB의 %s 테이블은 비어 있어야 한다",
                    table
            )
                    .isZero();
        }
    }

    private void awaitCompletion(
            long workspace,
            long recording
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (progress.findProgress(
                    workspace,
                    recording
            )
                    .orElseThrow()
                    .status() == DocumentGenerationProcessingStatus.SUCCEEDED) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(
                progress.findProgress(
                        workspace,
                        recording
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.SUCCEEDED);
    }
}
