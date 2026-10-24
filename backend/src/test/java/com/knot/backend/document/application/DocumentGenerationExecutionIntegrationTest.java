package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.application.dto.result.DocumentTopicClassificationResult;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import org.springframework.dao.DataAccessException;
import static org.mockito.ArgumentMatchers.eq;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(properties = {"knot.llm.enabled=true", "knot.llm.base-url=http://localhost:1234",
        "knot.llm.api-token=test-token", "knot.llm.model=test-model", "knot.document-generation.worker.enabled=false"})
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentGenerationExecutionIntegrationTest {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);
    private static final DocumentGenerationResult RESULT = new DocumentGenerationResult(
            "제목",
            "요약",
            "## 핵심 요약\n내용"
    );

    @Autowired
    private DocumentGenerationIntakeService intake;
    @Autowired
    private DocumentGenerationClaimService claims;
    @Autowired
    private DocumentGenerationWorker worker;
    @Autowired
    private DocumentGenerationFailureService failures;
    @Autowired
    private DocumentGenerationRecoveryService recovery;
    @Autowired
    private DocumentGenerationProgressService progress;
    @Autowired
    private DocumentGenerationJobRetryService retries;
    @Autowired
    private DocumentGenerationResultService results;

    @Autowired
    private DocumentClassificationResultService classificationResults;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DocumentGenerationJobRepository jobRepository;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private DocumentTopicClassificationService classifier;
    @MockitoBean
    private DocumentGenerationService generator;
    private DocumentFixtures fixtures;
    private long memberId;
    private long workspaceId;
    private long recordingId;
    private long classifierId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("녹음자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        long transcriptId = fixtures.saveTranscript(recordingId);
        classifierId = transactions.execute(
                status -> intake.acceptCompletedTranscript(
                        workspaceId,
                        recordingId,
                        transcriptId
                )
        )
                .classificationJobId();
        when(classifier.classify(anyString())).thenReturn(
                DocumentTopicClassificationResult.fromNames(
                        List.of(
                                "A",
                                "B",
                                "C"
                        )
                )
        );
        when(
                generator.generate(
                        anyString(),
                        anyString()
                )
        ).thenReturn(RESULT);
    }

    @Test
    @DisplayName("동시에 같은 작업을 확보해도 현재 실행은 하나만 접수한다")
    void claim_success_singleConsumer() throws Exception {
        // given
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call = () -> {
                barrier.await(
                        10,
                        TimeUnit.SECONDS
                );
                return claims.claim(
                        workspaceId,
                        classifierId
                )
                        .isPresent();
            };
            // when
            Future<Boolean> first = executor.submit(call);
            Future<Boolean> second = executor.submit(call);
            // then
            assertThat(
                    List.of(
                            first.get(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                            second.get(
                                    10,
                                    TimeUnit.SECONDS
                            )
                    )
            ).containsExactlyInAnyOrder(
                    true,
                    false
            );
        }
        assertThat(
                number(
                        "attempt_count",
                        classifierId
                )
        ).isEqualTo(1);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.RUNNING);
    }

    @Test
    @DisplayName("외부 호출은 트랜잭션 밖에서 실행하며 2개 성공 문서를 유지하고 실패 하나만 재시도한다")
    void execute_success_partialFailureAndUserRetry() {
        // given
        when(classifier.classify(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(
                        () -> transactions.execute(
                                status -> jdbc.sql("SELECT id FROM workspaces WHERE id = :id FOR UPDATE")
                                        .param(
                                                "id",
                                                workspaceId
                                        )
                                        .query(Long.class)
                                        .single()
                        )
                )
                        .get(
                                5,
                                TimeUnit.SECONDS
                        );
            }
            return DocumentTopicClassificationResult.fromNames(
                    List.of(
                            "A",
                            "B",
                            "C"
                    )
            );
        });
        when(
                generator.generate(
                        anyString(),
                        eq("C")
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE));
        // when
        worker.execute(
                workspaceId,
                classifierId
        );
        List<Long> jobs = generationIds();
        for (long job : jobs)
            worker.execute(
                    workspaceId,
                    job
            );
        // then
        assertThat(documentCount()).isEqualTo(2);
        long failed = jobs.getLast();
        assertThat(status(failed)).isEqualTo("QUEUED");
        assertThat(
                number(
                        "automatic_retry_count",
                        failed
                )
        ).isEqualTo(1);
        assertThat(
                number(
                        "user_retry_count",
                        failed
                )
        ).isZero();
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        worker.execute(
                workspaceId,
                failed
        );
        assertThat(documentCount()).isEqualTo(2);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .failedCount()
        ).isEqualTo(1);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.FAILED);
        retries.retry(
                workspaceId,
                memberId,
                failed
        );
        when(
                generator.generate(
                        anyString(),
                        eq("C")
                )
        ).thenReturn(RESULT);
        worker.execute(
                workspaceId,
                failed
        );
        assertThat(documentCount()).isEqualTo(3);
        assertThat(
                number(
                        "user_retry_count",
                        failed
                )
        ).isEqualTo(1);
        assertThat(
                number(
                        "attempt_count",
                        failed
                )
        ).isEqualTo(3);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationFailureCause.class, names = {"RATE_LIMITED", "INVALID_RESPONSE"})
    @DisplayName("429와 예외 응답은 5초 뒤 한 번만 자동 재시도하며 중복 실패 통지는 횟수를 늘리지 않는다")
    void failAttempt_success_boundedScheduledRetry(DocumentGenerationFailureCause cause) {
        // given
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        // when
        failures.failAttempt(
                workspaceId,
                classifierId,
                1,
                cause
        );
        failures.failAttempt(
                workspaceId,
                classifierId,
                1,
                cause
        );
        // then
        assertThat(
                number(
                        "attempt_count",
                        classifierId
                )
        ).isEqualTo(2);
        assertThat(
                number(
                        "automatic_retry_count",
                        classifierId
                )
        ).isEqualTo(1);
        assertThat(
                number(
                        "user_retry_count",
                        classifierId
                )
        ).isZero();
        assertThat(
                claims.claim(
                        workspaceId,
                        classifierId
                )
        ).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        failures.failAttempt(
                workspaceId,
                classifierId,
                2,
                DocumentGenerationFailureCause.TIMEOUT
        );
        assertThat(status(classifierId)).isEqualTo("FAILED");
        assertThat(
                number(
                        "attempt_count",
                        classifierId
                )
        ).isEqualTo(2);
    }

    @Test
    @DisplayName("중단된 실행을 새 시도로 회수하며 늦은 결과와 늦은 실패를 차단한다")
    void recoverExpired_success_fencedLateResponse() {
        // given
        worker.execute(
                workspaceId,
                classifierId
        );
        long job = generationIds().getFirst();
        claims.claim(
                workspaceId,
                job
        )
                .orElseThrow();
        // when
        when(clock.instant()).thenReturn(NOW.plusSeconds(150));
        assertThatThrownBy(
                () -> results.completeGeneration(
                        workspaceId,
                        job,
                        1,
                        RESULT
                )
        ).isInstanceOf(DocumentException.class);
        recovery.recoverExpired(
                workspaceId,
                job,
                1
        );
        recovery.recoverExpired(
                workspaceId,
                job,
                1
        );
        failures.failAttempt(
                workspaceId,
                job,
                1,
                DocumentGenerationFailureCause.INVALID_RESPONSE
        );
        // then
        assertThat(
                number(
                        "attempt_count",
                        job
                )
        ).isEqualTo(2);
        assertThat(status(job)).isEqualTo("QUEUED");
        when(clock.instant()).thenReturn(NOW.plusSeconds(155));
        worker.execute(
                workspaceId,
                job
        );
        assertThat(documentCount()).isEqualTo(1);
        assertThat(
                results.completeGeneration(
                        workspaceId,
                        job,
                        2,
                        RESULT
                )
        ).isPositive();
        assertThat(documentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패 Job을 정리한 뒤에도 녹음 종료 결과는 실패로 유지한다")
    void findProgress_success_preservesDeletedFailure() {
        // given
        when(classifier.classify(anyString())).thenThrow(new LlmException(LlmErrorCode.LLM_AUTHENTICATION_FAILED));
        worker.execute(
                workspaceId,
                classifierId
        );
        // when
        jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        classifierId
                )
                .update();
        // then
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.FAILED);
        assertThat(
                progress.findProgress(
                        workspaceId + 1,
                        recordingId
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("내용 없음은 생성 성공과 구분하며 재시도 대상이 아니다")
    void execute_success_noContent() {
        // given
        when(classifier.classify(anyString())).thenReturn(DocumentTopicClassificationResult.fromNames(List.of()));
        // when
        worker.execute(
                workspaceId,
                classifierId
        );
        // then
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.NO_CONTENT);
        assertThat(generationIds()).isEmpty();
        verifyNoInteractions(generator);
        assertThatThrownBy(
                () -> retries.retry(
                        workspaceId,
                        memberId,
                        classifierId
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("삭제된 Workspace의 대기 작업은 종료하고 LLM을 호출하지 않는다")
    void claim_failure_deletedWorkspace() {
        // given
        jdbc.sql("UPDATE workspaces SET deleted_at = :now WHERE id = :id")
                .param(
                        "now",
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
                classifierId
        );
        // then
        assertThat(status(classifierId)).isEqualTo("FAILED");
        assertThat(
                jdbc.sql("SELECT failure_cause FROM document_generation_jobs WHERE id = :id")
                        .param(
                                "id",
                                classifierId
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("WORKSPACE_DELETED");
        verifyNoInteractions(
                classifier,
                generator
        );
    }

    private List<Long> generationIds() {
        return jdbc.sql("SELECT id FROM document_generation_jobs WHERE stage = 'GENERATION' ORDER BY id")
                .query(Long.class)
                .list();
    }

    @Test
    @DisplayName("만료 전 회수와 성공 이후 실패 통지는 현재 결과를 바꾸지 않는다")
    void recoverExpired_failure_notExpiredOrAlreadySucceeded() {
        // given
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        // when
        recovery.recoverExpired(
                workspaceId,
                classifierId,
                1
        );
        // then
        assertThat(status(classifierId)).isEqualTo("RUNNING");
        assertThat(
                number(
                        "attempt_count",
                        classifierId
                )
        ).isEqualTo(1);
        failures.failAttempt(
                workspaceId,
                classifierId,
                0,
                DocumentGenerationFailureCause.TIMEOUT
        );
        assertThat(status(classifierId)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("두 번째 중단은 최종 실패로 남기고 무한 회수하지 않는다")
    void recoverExpired_failure_exhaustedAutomaticLimit() {
        // given
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(150));
        recovery.recoverExpired(
                workspaceId,
                classifierId,
                1
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(155));
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        // when
        when(clock.instant()).thenReturn(NOW.plusSeconds(305));
        recovery.recoverExpired(
                workspaceId,
                classifierId,
                2
        );
        // then
        assertThat(status(classifierId)).isEqualTo("FAILED");
        assertThat(
                number(
                        "automatic_retry_count",
                        classifierId
                )
        ).isEqualTo(1);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.FAILED);
        assertThat(
                jobRepository.findExpiredCandidates(
                        NOW.plusSeconds(305),
                        20
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("미래에 예약된 작업을 제외하고 실행 기한이 만료된 작업만 회수 후보로 조회한다")
    void findCandidates_success_dueTimesOnly() {
        // given
        assertThat(
                jobRepository.findReadyCandidates(
                        NOW,
                        1
                )
        ).hasSize(1);
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        // when & then
        assertThat(
                jobRepository.findReadyCandidates(
                        NOW,
                        1
                )
        ).isEmpty();
        assertThat(
                jobRepository.findExpiredCandidates(
                        NOW.plusSeconds(149),
                        1
                )
        ).isEmpty();
        assertThat(
                jobRepository.findExpiredCandidates(
                        NOW.plusSeconds(150),
                        1
                )
        ).hasSize(1);
        failures.failAttempt(
                workspaceId,
                classifierId,
                1,
                DocumentGenerationFailureCause.UNAVAILABLE
        );
        assertThat(
                jobRepository.findReadyCandidates(
                        NOW.plusSeconds(4),
                        1
                )
        ).isEmpty();
        assertThat(
                jobRepository.findReadyCandidates(
                        NOW.plusSeconds(5),
                        1
                )
        ).hasSize(1);
    }

    @Test
    @DisplayName("사용할 수 없는 원문은 작업 확보 중 최종 실패로 기록하고 호출하지 않는다")
    void claim_failure_invalidStoredInput() {
        // given
        jdbc.sql("UPDATE transcripts SET content = ' '")
                .update();
        // when
        worker.execute(
                workspaceId,
                classifierId
        );
        // then
        assertThat(status(classifierId)).isEqualTo("FAILED");
        assertThat(
                number(
                        "automatic_retry_count",
                        classifierId
                )
        ).isZero();
        verifyNoInteractions(
                classifier,
                generator
        );
    }

    @Test
    @DisplayName("실패 작업 하나를 삭제한 뒤 다른 작업을 재시도해도 삭제된 실패 집계를 보존한다")
    void findProgress_success_preservesDeletedGenerationFailure() {
        // given
        when(
                generator.generate(
                        anyString(),
                        eq("B")
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE));
        when(
                generator.generate(
                        anyString(),
                        eq("C")
                )
        ).thenThrow(new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE));
        worker.execute(
                workspaceId,
                classifierId
        );
        List<Long> jobs = generationIds();
        for (long job : jobs)
            worker.execute(
                    workspaceId,
                    job
            );
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        for (long job : jobs)
            worker.execute(
                    workspaceId,
                    job
            );
        assertThat(status(jobs.get(1))).isEqualTo("FAILED");
        assertThat(status(jobs.getLast())).isEqualTo("FAILED");
        jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobs.get(1)
                )
                .update();
        // when
        retries.retry(
                workspaceId,
                memberId,
                jobs.getLast()
        );
        when(
                generator.generate(
                        anyString(),
                        eq("C")
                )
        ).thenReturn(RESULT);
        worker.execute(
                workspaceId,
                jobs.getLast()
        );
        // then
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .succeededCount()
        ).isEqualTo(2);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .failedCount()
        ).isEqualTo(1);
        assertThat(
                progress.findProgress(
                        workspaceId,
                        recordingId
                )
                        .orElseThrow()
                        .status()
        ).isEqualTo(DocumentGenerationProcessingStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "classification", "generation"})
    @DisplayName("확보·분류 저장·문서 저장의 Workspace 잠금 대기를 3초로 제한한다")
    void execution_failure_boundedDatabaseLockWait(String operation) throws Exception {
        // given
        long targetId = classifierId;
        if (operation.equals("generation")) {
            worker.execute(
                    workspaceId,
                    classifierId
            );
            targetId = generationIds().getFirst();
        }
        if (!operation.equals("claim")) {
            claims.claim(
                    workspaceId,
                    targetId
            )
                    .orElseThrow();
        }
        Runnable blocked = blockedOperation(
                operation,
                targetId
        );
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.sql("SELECT id FROM workspaces WHERE id = :id FOR UPDATE")
                        .param(
                                "id",
                                workspaceId
                        )
                        .query(Long.class)
                        .single();
                held.countDown();
                try {
                    release.await(
                            10,
                            TimeUnit.SECONDS
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();
                    throw new AssertionError(exception);
                }
            }));
            assertThat(
                    held.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            try {
                // when & then
                assertThatThrownBy(blocked::run).isInstanceOf(DataAccessException.class)
                        .rootCause()
                        .hasMessageContaining("ERROR: canceling statement due to lock timeout");
            } finally {
                release.countDown();
                holder.get(
                        10,
                        TimeUnit.SECONDS
                );
            }
        }
        String expectedStatus = expectedStatusAfterLockTimeout(operation);
        assertThat(status(targetId)).isEqualTo(expectedStatus);
        assertThat(
                jdbc.sql("SELECT count(*) FROM documents")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                number(
                        "attempt_count",
                        targetId
                )
        ).isEqualTo(1);
    }

    private String expectedStatusAfterLockTimeout(String operation) {
        if (operation.equals("claim")) {
            return "QUEUED";
        }
        return "RUNNING";
    }

    private Runnable blockedOperation(
            String operation,
            long targetId
    ) {
        switch (operation) {
            case "claim" :
                return () -> claims.claim(
                        workspaceId,
                        targetId
                );
            case "classification" :
                return () -> classificationResults.completeClassification(
                        workspaceId,
                        targetId,
                        1,
                        DocumentTopicClassificationResult.fromNames(List.of("A"))
                );
            case "generation" :
                return () -> results.completeGeneration(
                        workspaceId,
                        targetId,
                        1,
                        RESULT
                );
            default :
                throw new AssertionError("알 수 없는 작업");
        }
    }

    @Test
    @DisplayName("실행 도중 Workspace가 삭제되면 만료 회수 시 재접수하지 않는다")
    void recoverExpired_failure_deletedWorkspace() {
        // given
        claims.claim(
                workspaceId,
                classifierId
        )
                .orElseThrow();
        jdbc.sql("UPDATE workspaces SET deleted_at = :now")
                .param(
                        "now",
                        Timestamp.from(NOW)
                )
                .update();
        when(clock.instant()).thenReturn(NOW.plusSeconds(150));
        // when
        recovery.recoverExpired(
                workspaceId,
                classifierId,
                1
        );
        // then
        assertThat(status(classifierId)).isEqualTo("FAILED");
        assertThat(
                number(
                        "attempt_count",
                        classifierId
                )
        ).isEqualTo(1);
        verifyNoInteractions(
                classifier,
                generator
        );
    }

    private int number(
            String column,
            long jobId
    ) {
        return jdbc.sql("SELECT " + column + " FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .query(Integer.class)
                .single();
    }

    private String status(long jobId) {
        return jdbc.sql("SELECT status FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .query(String.class)
                .single();
    }

    private long documentCount() {
        return jdbc.sql("SELECT count(*) FROM documents")
                .query(Long.class)
                .single();
    }
}
