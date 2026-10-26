package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.document.domain.repository.DocumentConfirmationRepository;
import com.knot.backend.global.config.DocumentCategoryProperties;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false",
        "knot.document-generation.categories.enabled=true"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentCategoryExecutionIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);
    private static final DocumentGenerationResult BODY = new DocumentGenerationResult(
            "녹음 품질 개선",
            "음성 처리 품질을 검증한다.",
            "## 핵심 요약\nSTT 품질을 측정한다."
    );

    @Autowired
    private DocumentGenerationIntakeService intake;
    @Autowired
    private DocumentGenerationWorker worker;
    @Autowired
    private DocumentGenerationClaimService claims;
    @Autowired
    private DocumentGenerationRecoveryService recovery;
    @Autowired
    private DocumentCategoryCheckpointService checkpoints;
    @Autowired
    private DocumentCategoryProperties properties;
    @Autowired
    private DocumentGenerationResultService results;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private DocumentTopicClassificationService classifier;
    @MockitoBean
    private DocumentGenerationService generator;
    @MockitoBean
    private DocumentCategoryAssigner assigner;
    @MockitoSpyBean
    private DocumentConfirmationRepository confirmations;
    @MockitoSpyBean
    private DocumentCategoryFallbackClassifier fallback;

    private long workspaceId;
    private long memberId;
    private long jobId;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("녹음자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        long recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        long transcriptId = fixtures.saveTranscript(recordingId);
        long classifierId = transactions.execute(
                status -> intake.acceptCompletedTranscript(
                        workspaceId,
                        recordingId,
                        transcriptId
                )
        )
                .classificationJobId();
        when(classifier.classify(anyString()))
                .thenReturn(DocumentTopicClassificationResult.fromNames(List.of("녹음 안정성")));
        when(
                generator.generate(
                        anyString(),
                        anyString()
                )
        ).thenReturn(BODY);
        worker.execute(
                workspaceId,
                classifierId
        );
        jobId = jdbc.sql("SELECT id FROM document_generation_jobs WHERE stage = 'GENERATION'")
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("유효 본문을 먼저 보관하고 배정과 공개가 완료될 때까지 목록에 노출하지 않는다")
    void execute_success_publishOnlyAfterAssignment() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return DocumentCategoryDecision.create(
                    "녹음·전사",
                    "음성 처리",
                    DocumentCategoryAssignmentSource.AI_NEW
            );
        });

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(count("documents")).isZero();
        assertThat(count("prepared_documents")).isEqualTo(1);
        assertThat(text("processing_phase")).isEqualTo("ASSIGN_CATEGORY");

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(count("documents")).isZero();
        assertThat(text("processing_phase")).isEqualTo("PUBLISH");

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(count("documents")).isEqualTo(1);
        assertThat(count("prepared_documents")).isZero();
        assertThat(count("document_confirmations")).isEqualTo(1);
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("녹음·전사");
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
        verify(
                assigner,
                times(1)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("배정 서버가 꺼져 있으면 재호출 없이 서버 규칙으로 완료하고 재시도 할당량을 보존한다")
    void execute_success_unavailableUsesRules() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_UNAVAILABLE));

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("녹음·전사");
        assertThat(number("user_retry_count")).isZero();
        assertThat(number("automatic_retry_count")).isZero();
        assertThat(number("category_attempt_count")).isEqualTo(1);
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("서버 분류 규칙 자체가 실패해도 기타로 공개하고 본문을 다시 생성하지 않는다")
    void execute_success_ruleFailureUsesLastResort() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_UNAVAILABLE));
        doThrow(new IllegalStateException("injected pure rule failure")).when(fallback)
                .classify(
                        anyString(),
                        anyString(),
                        anyString(),
                        any()
                );

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("기타");
        assertThat(text("category_last_failure")).isEqualTo("RULE_CLASSIFICATION_ERROR");
        assertThat(number("user_retry_count")).isZero();
        assertThat(number("automatic_retry_count")).isZero();
        assertThat(count("prepared_documents")).isZero();
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("잘못된 응답은 배정만 한 번 재시도하고 두 번째 실패 후 서버 규칙으로 완료한다")
    void execute_success_boundedAssignmentRetry() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenThrow(new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE));

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(number("category_attempt_count")).isEqualTo(1);
        assertThat(count("documents")).isZero();
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(number("category_attempt_count")).isEqualTo(2);
        assertThat(number("automatic_retry_count")).isZero();
        assertThat(number("user_retry_count")).isZero();
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
        verify(
                assigner,
                times(2)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("배정 호출 중 종료되면 예산을 보존하고 오래된 실행 결과를 거절한 뒤 재개한다")
    void execute_success_recoverWithoutRegeneratingBody() {
        // given
        worker.execute(
                workspaceId,
                jobId
        );
        var execution = claims.claim(
                workspaceId,
                jobId
        )
                .orElseThrow();
        checkpoints.reserve(execution);
        when(clock.instant()).thenReturn(NOW.plusSeconds(151));

        // when
        recovery.recoverExpired(
                workspaceId,
                jobId,
                execution.attemptCount()
        );

        // then
        assertThat(text("status")).isEqualTo("QUEUED");
        assertThat(number("category_attempt_count")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> checkpoints.decide(
                        execution,
                        DocumentCategoryDecision.fallback(),
                        "obsolete"
                )
        )
                .isInstanceOf(DocumentException.class);

        // when
        when(clock.instant()).thenReturn(NOW.plusSeconds(156));
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
        verify(
                assigner,
                times(0)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("완료 저장이 롤백돼도 배정 결정과 본문을 유지하고 DB 저장만 다시 시도한다")
    void execute_success_retryPublicationOnly() {
        // given
        double before = publicationCount();
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.create(
                        "녹음·전사",
                        "음성 처리",
                        DocumentCategoryAssignmentSource.AI_NEW
                )
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        doThrow(new DataIntegrityViolationException("test write failure")).doCallRealMethod()
                .when(confirmations)
                .saveAll(any());

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(count("documents")).isZero();
        assertThat(count("document_categories")).isZero();
        assertThat(count("prepared_documents")).isEqualTo(1);
        assertThat(text("processing_phase")).isEqualTo("PUBLISH");
        assertThat(text("status")).isEqualTo("QUEUED");
        assertThat(publicationCount()).isEqualTo(before);

        // when
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(count("documents")).isEqualTo(1);
        assertThat(publicationCount()).isEqualTo(before + 1);
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
        verify(
                assigner,
                times(1)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("기능을 꺼도 이미 보관된 본문은 큰 주제와 함께 완료한다")
    void execute_success_finishPreparedAfterDisabling() {
        // given
        worker.execute(
                workspaceId,
                jobId
        );
        properties.setEnabled(false);
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_AUTHENTICATION_FAILED));

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("녹음·전사");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("본문을 보관한 Job은 기존 완료 메서드로 배정을 건너뛰어 공개하지 못한다")
    void completeGeneration_failure_cannotBypassAssignment() {
        // given
        worker.execute(
                workspaceId,
                jobId
        );
        var execution = claims.claim(
                workspaceId,
                jobId
        )
                .orElseThrow();

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> results.completeGeneration(
                        workspaceId,
                        jobId,
                        execution.attemptCount(),
                        BODY
                )
        )
                .isInstanceOf(DocumentException.class);
        assertThat(count("documents")).isZero();
        assertThat(count("prepared_documents")).isEqualTo(1);
    }

    private double publicationCount() {
        return metrics.find("knot.document.category.publications")
                .counters()
                .stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Test
    @DisplayName("동시 공개에서 같은 정규 이름의 큰 주제는 하나만 생성한다")
    void execute_success_concurrentExactCategoryReuse() throws Exception {
        // given
        long first = jobId;
        long second = anotherJob("전사 품질");
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.create(
                        "녹음·전사",
                        "음성 처리",
                        DocumentCategoryAssignmentSource.AI_NEW
                )
        );
        for (long id : List.of(
                first,
                second
        )) {
            worker.execute(
                    workspaceId,
                    id
            );
            worker.execute(
                    workspaceId,
                    id
            );
        }
        CyclicBarrier barrier = new CyclicBarrier(2);

        // when
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                barrier.await(
                        10,
                        TimeUnit.SECONDS
                );
                worker.execute(
                        workspaceId,
                        first
                );
                return true;
            });
            var two = executor.submit(() -> {
                barrier.await(
                        10,
                        TimeUnit.SECONDS
                );
                worker.execute(
                        workspaceId,
                        second
                );
                return true;
            });
            one.get(
                    10,
                    TimeUnit.SECONDS
            );
            two.get(
                    10,
                    TimeUnit.SECONDS
            );
        }

        // then
        assertThat(count("documents")).isEqualTo(2);
        assertThat(count("document_categories")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(DISTINCT category_id) FROM documents")
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        verify(
                generator,
                times(2)
        ).generate(
                anyString(),
                anyString()
        );
        verify(
                assigner,
                times(2)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("신규 생성 직전 후보가 바뀌면 남은 예산 안에서 최신 후보를 다시 검토한다")
    void execute_success_reviewChangedCatalog() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.create(
                        "음성 자료",
                        "녹음 처리",
                        DocumentCategoryAssignmentSource.AI_NEW
                )
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        long added = saveCategory(
                workspaceId,
                "녹음·전사",
                "음성 및 STT 처리"
        );

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("processing_phase")).isEqualTo("ASSIGN_CATEGORY");
        assertThat(count("documents")).isZero();
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenAnswer(invocation -> {
            com.knot.backend.document.application.dto.request.DocumentCategoryInput input = invocation.getArgument(0);
            assertThat(input.categories()).extracting(com.knot.backend.document.domain.model.DocumentCategory::getId)
                    .containsExactly(added);
            return DocumentCategoryDecision.reuse(
                    added,
                    DocumentCategoryAssignmentSource.AI_REUSE
            );
        });

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(number("category_attempt_count")).isEqualTo(2);
        assertThat(count("document_categories")).isEqualTo(1);
        assertThat(categoryName()).isEqualTo("녹음·전사");
    }

    @Test
    @DisplayName("계속 후보가 바뀌어도 배정 예산을 갱신하지 않고 서버 규칙으로 종료한다")
    void execute_success_catalogChurnIsBounded() {
        // given
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.create(
                        "음성 자료",
                        "녹음 처리",
                        DocumentCategoryAssignmentSource.AI_NEW
                )
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        saveCategory(
                workspaceId,
                "배포·운영",
                "서비스 배포"
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        saveCategory(
                workspaceId,
                "녹음·전사",
                "음성 및 STT 처리"
        );

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(number("category_attempt_count")).isEqualTo(2);
        assertThat(categoryName()).isEqualTo("녹음·전사");
        verify(
                assigner,
                times(2)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("다른 Workspace 후보 ID를 반환해도 문서를 연결하지 않고 서버 규칙으로 완료한다")
    void execute_success_rejectForeignCategory() {
        // given
        long foreignWorkspace = new DocumentFixtures(jdbc).saveWorkspace();
        long foreignCategory = saveCategory(
                foreignWorkspace,
                "외부 큰 주제",
                "외부 범위"
        );
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.reuse(
                        foreignCategory,
                        DocumentCategoryAssignmentSource.AI_REUSE
                )
        );

        // when
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("녹음·전사");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.sql("UPDATE documents SET category_id = :id")
                        .param(
                                "id",
                                foreignCategory
                        )
                        .update()
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("규칙도 모르는 안건은 최후 기타를 재사용하고 완료 뒤 자동 이동하지 않는다")
    void execute_success_reuseLastResortCategory() {
        // given
        when(
                generator.generate(
                        anyString(),
                        eq("철학 토론")
                )
        ).thenReturn(
                new DocumentGenerationResult(
                        "니체의 행복",
                        "철학 논의",
                        "## 핵심 요약\n행복과 고통을 논의했다."
                )
        );
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_UNAVAILABLE));
        long first = anotherJob("철학 토론");
        long second = anotherJob("철학 토론");

        // when
        for (long id : List.of(
                first,
                second
        )) {
            worker.execute(
                    workspaceId,
                    id
            );
            worker.execute(
                    workspaceId,
                    id
            );
            worker.execute(
                    workspaceId,
                    id
            );
            worker.execute(
                    workspaceId,
                    id
            );
        }

        // then
        assertThat(count("documents")).isEqualTo(2);
        assertThat(count("document_categories")).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT name FROM document_categories")
                        .query(String.class)
                        .single()
        ).isEqualTo("기타");
        assertThat(
                jdbc.sql("SELECT DISTINCT assignment_source FROM documents")
                        .query(String.class)
                        .single()
        ).isEqualTo("FALLBACK");
        verify(
                assigner,
                times(2)
        ).assign(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("기간이 지나도 진행 중 임시 본문과 원문은 남고 삭제 Workspace의 Job은 문서를 공개하지 않는다")
    void execute_success_preparedDocumentKeptAndWorkspaceDeletionNotPublished() {
        // given
        worker.execute(
                workspaceId,
                jobId
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(8 * 24 * 60 * 60));
        jdbc.sql("UPDATE workspaces SET deleted_at = :time WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(NOW)
                )
                .param(
                        "id",
                        workspaceId
                )
                .update();

        // when
        worker.execute(
                workspaceId,
                jobId
        );

        // then
        assertThat(text("status")).isEqualTo("FAILED");
        assertThat(text("failure_cause")).isEqualTo("WORKSPACE_DELETED");
        assertThat(count("documents")).isZero();
        assertThat(count("prepared_documents")).isEqualTo(1);
        assertThat(count("transcripts")).isEqualTo(1);
    }

    private long anotherJob(String topic) {
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long recording = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        long transcript = fixtures.saveTranscript(recording);
        long classifierJob = transactions.execute(
                status -> intake.acceptCompletedTranscript(
                        workspaceId,
                        recording,
                        transcript
                )
        )
                .classificationJobId();
        when(classifier.classify(anyString())).thenReturn(DocumentTopicClassificationResult.fromNames(List.of(topic)));
        worker.execute(
                workspaceId,
                classifierJob
        );
        return jdbc.sql("SELECT id FROM document_generation_jobs WHERE transcript_id = :id AND stage = 'GENERATION'")
                .param(
                        "id",
                        transcript
                )
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("퇴역한 후보를 선택한 준비 문서는 본문을 재생성하지 않고 최신 큰 주제로 다시 배정한다")
    void execute_success_retiredCandidateReassigned() {
        long retired = saveCategory(
                workspaceId,
                "음성 처리",
                "음성과 STT 처리"
        );
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.reuse(
                        retired,
                        DocumentCategoryAssignmentSource.AI_REUSE
                )
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        jdbc.sql("UPDATE document_categories SET active_for_assignment=false WHERE id=:id")
                .param(
                        "id",
                        retired
                )
                .update();
        long active = saveCategory(
                workspaceId,
                "녹음·전사",
                "녹음 수집과 전사 품질"
        );
        worker.execute(
                workspaceId,
                jobId
        );
        assertThat(text("processing_phase")).isEqualTo("ASSIGN_CATEGORY");
        assertThat(count("prepared_documents")).isEqualTo(1);
        when(
                assigner.assign(
                        any(),
                        any()
                )
        ).thenReturn(
                DocumentCategoryDecision.reuse(
                        active,
                        DocumentCategoryAssignmentSource.AI_REUSE
                )
        );
        worker.execute(
                workspaceId,
                jobId
        );
        worker.execute(
                workspaceId,
                jobId
        );
        assertThat(text("status")).isEqualTo("SUCCEEDED");
        assertThat(categoryName()).isEqualTo("녹음·전사");
        verify(
                generator,
                times(1)
        ).generate(
                anyString(),
                anyString()
        );
    }

    private long saveCategory(
            long workspace,
            String name,
            String description
    ) {
        return jdbc.sql("""
                INSERT INTO document_categories(workspace_id, name, normalized_name, description, kind, created_at)
                VALUES (:workspace, :name, :name, :description, 'NORMAL', :time) RETURNING id
                """)
                .param(
                        "workspace",
                        workspace
                )
                .param(
                        "name",
                        name
                )
                .param(
                        "description",
                        description
                )
                .param(
                        "time",
                        Timestamp.from(NOW)
                )
                .query(Long.class)
                .single();
    }

    private String text(String column) {
        return jdbc.sql("SELECT " + column + " FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .query(String.class)
                .single();
    }

    private int number(String column) {
        return jdbc.sql("SELECT " + column + " FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .query(Integer.class)
                .single();
    }

    private String categoryName() {
        return jdbc.sql("SELECT c.name FROM documents d JOIN document_categories c ON c.id = d.category_id")
                .query(String.class)
                .single();
    }
}
