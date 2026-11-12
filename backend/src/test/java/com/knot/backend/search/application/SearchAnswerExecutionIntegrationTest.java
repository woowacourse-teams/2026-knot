package com.knot.backend.search.application;

import static org.mockito.ArgumentMatchers.any;
import com.knot.backend.search.domain.model.SearchIntent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchAnswerExecutionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService service;
    @Autowired
    private SearchAnswerWorker worker;
    @Autowired
    private SearchToolAccess toolAccess;
    @Autowired
    private SearchAnswerSnapshotService snapshots;
    @Autowired
    private com.knot.backend.search.domain.repository.SearchRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoBean
    private SearchDocumentRetriever retriever;
    @MockitoBean
    private SearchIntentClassifier classifier;
    @MockitoBean
    private SearchAnswerGenerator generator;
    @MockitoBean
    private Clock clock;
    private DocumentFixtures fixtures;
    private SearchMessageFixtures messages;
    private long workspaceId;
    private long memberId;
    private SearchQuestionAcceptedResult accepted;

    @BeforeEach
    void setUp() {
        when(
                classifier.classify(
                        any(),
                        any()
                )
        ).thenReturn(SearchIntent.MEETING_SEARCH);
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        messages = new SearchMessageFixtures(jdbc);
        memberId = fixtures.saveMember("질문자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        accepted = intake.acceptFirst(
                workspaceId,
                memberId,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
    }

    @Test
    @DisplayName("두 worker가 같은 작업을 선점하면 한 실행만 얻는다")
    void claim_success_concurrent() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Boolean> first = executor.submit(() -> {
                start.await();
                return service.claim(accepted.answerMessageId())
                        .isPresent();
            });
            java.util.concurrent.Future<Boolean> second = executor.submit(() -> {
                start.await();
                return service.claim(accepted.answerMessageId())
                        .isPresent();
            });
            assertThat(
                    List.of(
                            first.get(),
                            second.get()
                    )
            ).containsExactlyInAnyOrder(
                    true,
                    false
            );
        }
    }

    @Test
    @DisplayName("기술 오류는 미검증 본문 대신 안내 응답을 저장하고 최초 대화를 보존한다")
    void fail_success_partialAndHidden() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        assertThat(work.createdAt()).isEqualTo(NOW);
        assertThat(
                service.append(
                        work.answerId(),
                        UUID.randomUUID(),
                        "오래된 실행"
                )
        ).isFalse();
        assertThat(
                service.append(
                        work.answerId(),
                        work.token(),
                        "한글 😀"
                )
        ).isTrue();
        assertThat(
                service.fail(
                        work.answerId(),
                        work.token(),
                        "ANSWER_GENERATION_FAILED"
                )
        ).isTrue();
        assertThat(
                service.append(
                        work.answerId(),
                        work.token(),
                        "늦은 본문"
                )
        ).isFalse();
        assertThat(content()).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(visible()).isTrue();
    }

    @Test
    @DisplayName("완료 본문과 실제 사용 근거를 함께 저장하고 근거 순위를 유지한다")
    void complete_success_usedEvidenceOnly() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        long first = archivedDocument(
                workspaceId,
                memberId
        );
        long second = archivedDocument(
                workspaceId,
                memberId
        );
        long candidate = archivedDocument(
                workspaceId,
                memberId
        );
        service.append(
                work.answerId(),
                work.token(),
                "답변"
        );
        assertThat(
                service.complete(
                        work.answerId(),
                        work.token(),
                        List.of(
                                second,
                                first
                        ),
                        Set.of(
                                first,
                                second,
                                candidate
                        )
                )
        ).isTrue();
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences ORDER BY rank")
                        .query(Long.class)
                        .list()
        ).containsExactly(
                second,
                first
        );
        assertThat(
                service.fail(
                        work.answerId(),
                        work.token(),
                        "ANSWER_GENERATION_FAILED"
                )
        ).isFalse();
        assertThat(content()).isEqualTo("답변");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENIED", "EXPIRED_SCOPE", "DENIED_AFTER_DEADLINE"})
    @DisplayName("기한 뒤에도 실제 권한 거절은 보존하고 실행 범위 만료만 안내로 완료한다")
    void execute_failure_distinguishesAccessFromTimeout(String scenario) {
        doAnswer(call -> {
            if (!"DENIED".equals(scenario)) {
                when(clock.instant()).thenReturn(NOW.plusSeconds(120));
            }
            if ("EXPIRED_SCOPE".equals(scenario)) {
                toolAccess.validate(call.getArgument(0));
            }
            throw new com.knot.backend.search.domain.SearchToolException("SEARCH_ACCESS_DENIED");
        }).when(classifier)
                .classify(
                        any(),
                        any()
                );
        worker.execute(accepted.answerMessageId());
        verifyNoInteractions(
                retriever,
                generator
        );
        if ("EXPIRED_SCOPE".equals(scenario)) {
            assertThat(status()).isEqualTo("COMPLETED");
            assertThat(content()).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
            assertThat(visible()).isTrue();
            return;
        }
        assertThat(status()).isEqualTo("FAILED");
        assertThat(content()).isEmpty();
        assertThat(visible()).isFalse();
        assertThat(
                jdbc.sql("SELECT failure_code FROM search_messages WHERE id=:id")
                        .param(
                                "id",
                                accepted.answerMessageId()
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("SEARCH_ACCESS_DENIED");
    }

    @Test
    @DisplayName("생성 본문과 출처를 완료할 때 출처 검증이 거절되면 본문도 롤백한다")
    void completeGenerated_failure_rollsBackText() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        assertThatThrownBy(
                () -> service.completeGenerated(
                        work.answerId(),
                        work.token(),
                        "미검증 본문",
                        List.of(99999L),
                        Set.of()
                )
        ).isInstanceOf(SearchException.class);
        assertThat(content()).isEmpty();
        assertThat(status()).isEqualTo("STREAMING");
        assertThat(evidenceCount()).isZero();
        assertThat(
                service.fail(
                        work.answerId(),
                        work.token(),
                        "ANSWER_GENERATION_FAILED"
                )
        ).isTrue();
        assertThat(content()).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        assertThat(status()).isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_RETRIEVED", "DUPLICATE", "NULL"})
    @DisplayName("검색 후보에 없는 문서와 중복 또는 null 근거는 커스텀 오류로 거절한다")
    void complete_failure_invalidEvidence(String scenario) {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        long document = archivedDocument(
                workspaceId,
                memberId
        );
        List<Long> ids = invalidEvidenceIds(
                scenario,
                document
        );
        Set<Long> candidates = Set.of();
        if (ids != null) {
            candidates = Set.copyOf(ids);
        }
        if ("NOT_RETRIEVED".equals(scenario)) {
            candidates = Set.of();
        }
        Set<Long> selected = candidates;
        assertThatThrownBy(
                () -> service.complete(
                        work.answerId(),
                        work.token(),
                        ids,
                        selected
                )
        ).isInstanceOf(SearchException.class);
        assertThat(status()).isEqualTo("STREAMING");
        assertThat(evidenceCount()).isZero();
    }

    private List<Long> invalidEvidenceIds(
            String scenario,
            long document
    ) {
        if ("DUPLICATE".equals(scenario)) {
            return List.of(
                    document,
                    document
            );
        }
        if ("NULL".equals(scenario)) {
            return null;
        }
        return List.of(document);
    }

    @Test
    @DisplayName("다른 Workspace 문서를 사용하면 본문 종료와 근거가 함께 롤백한다")
    void complete_failure_otherWorkspaceEvidence() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        long valid = messages.saveDocument(
                workspaceId,
                memberId
        );
        long otherWorkspace = fixtures.saveWorkspace();
        fixtures.join(
                otherWorkspace,
                memberId
        );
        long invalid = archivedDocument(
                otherWorkspace,
                memberId
        );
        assertThatThrownBy(
                () -> service.complete(
                        work.answerId(),
                        work.token(),
                        List.of(
                                valid,
                                invalid
                        ),
                        Set.of(
                                valid,
                                invalid
                        )
                )
        ).isInstanceOf(SearchException.class);
        assertThat(status()).isEqualTo("STREAMING");
        assertThat(evidenceCount()).isZero();
    }

    @Test
    @DisplayName("기한에 도달하면 안내로 완료하고 자동 재생성이나 늦은 결과를 허용하지 않는다")
    void expire_success_preservesPartialAndFencesLateResults() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        service.append(
                work.answerId(),
                work.token(),
                "부분"
        );
        when(clock.instant()).thenReturn(NOW.plusSeconds(150));
        assertThat(
                service.append(
                        work.answerId(),
                        work.token(),
                        "늦음"
                )
        ).isFalse();
        assertThat(service.expire(work.answerId())).isTrue();
        assertThat(service.expire(work.answerId())).isFalse();
        assertThat(
                service.complete(
                        work.answerId(),
                        work.token(),
                        List.of(),
                        Set.of()
                )
        ).isFalse();
        assertThat(service.claim(work.answerId())).isEmpty();
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_messages WHERE id = :id AND failure_code IS NOT NULL")
                        .param(
                                "id",
                                work.answerId()
                        )
                        .query(Integer.class)
                        .single()
        ).isZero();
        assertThat(visible()).isTrue();
    }

    @Test
    @DisplayName("기한 전 실행과 미실행 대기 작업을 실패 복구로 종료시키지 않는다")
    void expire_success_preservesActiveAndQueued() {
        assertThat(service.expire(accepted.answerMessageId())).isFalse();
        service.claim(accepted.answerMessageId())
                .orElseThrow();
        assertThat(service.expire(accepted.answerMessageId())).isFalse();
        assertThat(status()).isEqualTo("STREAMING");
    }

    @Test
    @DisplayName("근거가 없어도 생성 모델의 일반 답변을 저장한다")
    void execute_success_noDocuments() {
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("일반적인 설명이에요.");
            return List.of();
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );

        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new SearchRetrievalResult(
                    List.of(),
                    2
            );
        });
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo("일반적인 설명이에요.");
        assertThat(evidenceCount()).isZero();
        assertThat(
                jdbc.sql("SELECT excluded_count FROM search_answer_executions")
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
    }

    @Test
    @DisplayName("생성은 TX 밖에서 진행하며 delta와 실제 사용 문서만 저장한다")
    void execute_success_generatorOutsideTransaction() {
        long document = archivedDocument(
                workspaceId,
                memberId
        );
        List<SearchDocumentResult> candidates = List.of(
                new SearchDocumentResult(
                        document,
                        "문서",
                        "주제",
                        "본문"
                )
        );
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        candidates,
                        1
                )
        );
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Consumer<String> delta = call.getArgument(3);
            delta.accept("한글 ");
            delta.accept("😀");
            return List.of(document);
        }).when(generator)
                .generate(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문"),
                        org.mockito.ArgumentMatchers.eq(candidates),
                        org.mockito.ArgumentMatchers.any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo("한글 😀");
        assertThat(evidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("검색 오류는 조회 불가를 밝히고 일반 답변을 이어 저장한다")
    void execute_failure_retrievalError() {
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("일반적인 설명이에요.");
            return List.of();
        }).when(generator)
                .generate(
                        any(),
                        any(),
                        any(),
                        any()
                );

        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenThrow(new IllegalStateException("비공개 공급자 오류"));
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo(SearchAnswerGuidance.RETRIEVAL_UNAVAILABLE + "일반적인 설명이에요.");
        assertThat(visible()).isTrue();
    }

    @Test
    @DisplayName("동시 완료·실패는 먼저 확정된 종료만 남기고 이후 상태를 덮어쓰지 않는다")
    void complete_success_terminalRace() throws Exception {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        service.append(
                work.answerId(),
                work.token(),
                "부분 답변"
        );
        CyclicBarrier start = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Boolean> completed = executor.submit(() -> {
                start.await();
                return service.complete(
                        work.answerId(),
                        work.token(),
                        List.of(),
                        Set.of()
                );
            });
            java.util.concurrent.Future<Boolean> failed = executor.submit(() -> {
                start.await();
                return service.fail(
                        work.answerId(),
                        work.token(),
                        "ANSWER_GENERATION_FAILED"
                );
            });
            assertThat(
                    List.of(
                            completed.get(),
                            failed.get()
                    )
            ).containsExactlyInAnyOrder(
                    true,
                    false
            );
            assertThat(status()).isEqualTo("COMPLETED");
            String expected = SearchAnswerGuidance.UNAVAILABLE;
            if (completed.get()) {
                expected = "부분 답변";
            }
            assertThat(content()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("생성 중 오류는 미검증 본문을 안내로 교체하고 근거를 만들지 않는다")
    void execute_failure_preservesGeneratedPartial() {
        long document = archivedDocument(
                workspaceId,
                memberId
        );
        List<SearchDocumentResult> candidates = List.of(
                new SearchDocumentResult(
                        document,
                        "문서",
                        "주제",
                        "본문"
                )
        );
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        candidates,
                        0
                )
        );
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            delta.accept("이미 생성한 부분 😀");
            throw new IllegalStateException("공급자 연결 오류");
        }).when(generator)
                .generate(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문"),
                        org.mockito.ArgumentMatchers.eq(candidates),
                        org.mockito.ArgumentMatchers.any()
                );
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(content()).isEqualTo(SearchAnswerGuidance.UNAVAILABLE);
        assertThat(evidenceCount()).isZero();
    }

    private String content() {
        return jdbc.sql("SELECT content FROM search_messages WHERE id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM search_messages WHERE id = :id")
                .param(
                        "id",
                        accepted.answerMessageId()
                )
                .query(String.class)
                .single();
    }

    private boolean visible() {
        return jdbc.sql("SELECT visible_in_list FROM search_conversations WHERE id = :id")
                .param(
                        "id",
                        accepted.conversationId()
                )
                .query(Boolean.class)
                .single();
    }

    @Test
    @DisplayName("실제 사용한 네 문서는 모두 저장하고 SSE 카드에는 상위 세 개만 노출한다")
    void complete_success_allEvidenceAndThreeCards() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        List<Long> documents = java.util.stream.LongStream.range(
                0,
                4
        )
                .map(
                        index -> archivedDocument(
                                workspaceId,
                                memberId
                        )
                )
                .boxed()
                .toList();
        service.append(
                work.answerId(),
                work.token(),
                "네 회의 종합"
        );
        assertThat(
                service.complete(
                        work.answerId(),
                        work.token(),
                        documents,
                        new java.util.HashSet<>(documents)
                )
        ).isTrue();
        assertThat(evidenceCount()).isEqualTo(4);
        assertThat(
                snapshots.read(
                        workspaceId,
                        memberId,
                        work.answerId()
                )
                        .evidences()
        ).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ARCHIVED"})
    @DisplayName("DRAFT 후보는 보관 전환 여부와 무관하게 실제 사용 근거와 완료 상태를 저장한다")
    void complete_success_draftEvidence(String terminalDocumentStatus) {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        long draft = messages.saveDocument(
                workspaceId,
                memberId
        );
        assertThat(
                service.recordRetrieval(
                        work.answerId(),
                        work.token(),
                        0,
                        List.of(
                                new SearchDocumentResult(
                                        draft,
                                        "초안",
                                        "주제",
                                        "본문"
                                )
                        )
                )
        ).isTrue();
        if ("ARCHIVED".equals(terminalDocumentStatus)) {
            jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = :time WHERE id = :id")
                    .param(
                            "time",
                            java.sql.Timestamp.from(NOW.plusSeconds(1))
                    )
                    .param(
                            "id",
                            draft
                    )
                    .update();
        }
        service.append(
                work.answerId(),
                work.token(),
                "DRAFT 근거 답변"
        );
        assertThat(
                service.complete(
                        work.answerId(),
                        work.token(),
                        List.of(draft),
                        Set.of(draft)
                )
        ).isTrue();
        assertThat(evidenceCount()).isEqualTo(1);
        assertThat(
                snapshots.read(
                        workspaceId,
                        memberId,
                        work.answerId()
                )
                        .status()
                        .name()
        ).isEqualTo("COMPLETED");
        assertThat(
                jdbc.sql("SELECT status FROM documents WHERE id = :id")
                        .param(
                                "id",
                                draft
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo(terminalDocumentStatus);
    }

    private long archivedDocument(
            long workspace,
            long member
    ) {
        long id = messages.saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = created_at WHERE id = :id")
                .param(
                        "id",
                        id
                )
                .update();
        return id;
    }

    private long evidenceCount() {
        return jdbc.sql("SELECT COUNT(*) FROM search_evidences")
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("재시작 때 남아 있는 대기 작업은 DB 후보 조회에서 다시 실행할 수 있다")
    void execute_success_durableQueuedWork() {
        assertThat(repository.findQueuedAnswerIds(20)).containsExactly(accepted.answerMessageId());
        when(
                retriever.retrieve(
                        org.mockito.ArgumentMatchers.any(com.knot.backend.search.application.SearchToolScope.class),
                        org.mockito.ArgumentMatchers.eq("질문")
                )
        ).thenReturn(
                new SearchRetrievalResult(
                        List.of(),
                        0
                )
        );
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = org.mockito.Mockito
                .mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        doAnswer(call -> {
            Runnable task = call.getArgument(0);
            task.run();
            return null;
        }).when(executor)
                .execute(org.mockito.ArgumentMatchers.any());
        com.knot.backend.search.infrastructure.SearchAnswerScheduler restarted = new com.knot.backend.search.infrastructure.SearchAnswerScheduler(
                repository,
                worker,
                service,
                new com.knot.backend.global.config.SearchWorkerProperties(),
                executor,
                clock
        );
        restarted.dispatchQueued();
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(repository.findQueuedAnswerIds(20)).isEmpty();
    }

    @Test
    @DisplayName("snapshot은 소유자 범위와 현재 멤버십을 확인하고 생성 상태를 변경하지 않는다")
    void read_success_currentSnapshotAndAccess() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        service.append(
                work.answerId(),
                work.token(),
                "부분 😀"
        );
        SearchAnswerSnapshotResult snapshot = snapshots.read(
                workspaceId,
                memberId,
                work.answerId()
        );
        assertThat(snapshot.content()).isEqualTo("부분 😀");
        assertThat(snapshot.status()).isEqualTo(com.knot.backend.search.domain.model.SearchMessageStatus.STREAMING);
        assertThat(snapshot.evidences()).isEmpty();
        assertThat(status()).isEqualTo("STREAMING");
        long otherMember = fixtures.saveMember("다른 사용자");
        fixtures.join(
                workspaceId,
                otherMember
        );
        assertThatThrownBy(
                () -> snapshots.read(
                        workspaceId,
                        otherMember,
                        work.answerId()
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchException.class);
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP WHERE member_id = :id")
                .param(
                        "id",
                        memberId
                )
                .update();
        assertThatThrownBy(
                () -> snapshots.read(
                        workspaceId,
                        memberId,
                        work.answerId()
                )
        ).isInstanceOf(com.knot.backend.search.domain.SearchException.class);
    }

    @Test
    @DisplayName("실행 전 멤버십이 회수되면 문서를 읽지 않고 실패 종료한다")
    void claim_failure_revokedMembership() {
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        worker.execute(accepted.answerMessageId());
        assertThat(status()).isEqualTo("FAILED");
        assertThat(visible()).isFalse();
        verifyNoInteractions(
                retriever,
                generator
        );
    }

    @Test
    @DisplayName("실행 중 멤버십이 회수되면 이후 delta를 저장하지 않고 실패 종료한다")
    void append_failure_revokedMembership() {
        SearchAnswerWorkResult work = service.claim(accepted.answerMessageId())
                .orElseThrow();
        service.append(
                work.answerId(),
                work.token(),
                "기존 본문"
        );
        jdbc.sql("UPDATE workspace_members SET left_at = CURRENT_TIMESTAMP")
                .update();
        assertThat(
                service.append(
                        work.answerId(),
                        work.token(),
                        "추가 본문"
                )
        ).isFalse();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(content()).isEqualTo("기존 본문");
    }
}
