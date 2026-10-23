package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentListQuery;
import com.knot.backend.document.application.dto.query.DocumentListParameters;
import com.knot.backend.document.application.dto.result.DocumentCardResult;
import com.knot.backend.document.application.dto.result.DocumentTopicResult;
import com.knot.backend.document.domain.model.DocumentCursor;
import com.knot.backend.document.domain.model.DocumentConfirmation;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.testsupport.SharedPostgresDocumentQueryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({SharedPostgresTestConfiguration.class, DocumentConfirmationQueryAdapter.class,
        DocumentDetailQueryAdapter.class, DocumentGenerationJobListQueryAdapter.class, DocumentListQueryAdapter.class})
class DocumentListQueryIntegrationTest extends SharedPostgresDocumentQueryTestSupport {
    @Autowired
    private DocumentListQuery query;
    @Autowired
    private DocumentListJpaRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("조회자");
        fixtures.join(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("주제 수는 페이지 범위와 무관하고 동률 문서는 ID 내림차순으로 이어 읽는다")
    void find_success_countsAndCursor() {
        long first = saveDocument(
                workspaceId,
                "정책"
        );
        long second = saveDocument(
                workspaceId,
                "정책"
        );
        long third = saveDocument(
                workspaceId,
                "개발"
        );
        saveDocument(
                fixtures.saveWorkspace(),
                "외부"
        );
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                1,
                null,
                null
        );

        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        parameters
                )
        ).extracting(topic -> topic.topic() + ":" + topic.documentCount())
                .containsExactly(
                        "개발:1",
                        "정책:2"
                );
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        parameters,
                        null
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(
                        third,
                        second
                );
        DocumentCursor cursor = DocumentCursor.of(
                workspaceId,
                memberId,
                null,
                null,
                DocumentFixtures.CREATED_AT,
                second
        );
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        parameters,
                        cursor
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(first);
        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        parameters
                )
        ).hasSize(2);
    }

    @Test
    @DisplayName("기존 응답으로 여러 안건을 큰 주제에 모으고 저장된 안건은 보존한다")
    void find_success_broadFoldersWithoutChangingAgendas() {
        // given
        long first = saveDocument(
                workspaceId,
                "녹음 안정성"
        );
        long second = saveDocument(
                workspaceId,
                "전사 품질"
        );
        long legacy = saveDocument(
                workspaceId,
                "녹음·전사"
        );
        var category = com.knot.backend.document.domain.model.DocumentCategory.create(
                workspaceId,
                "녹음·전사",
                "음성 처리",
                DocumentFixtures.CREATED_AT
        );
        entityManager.persist(category);
        entityManager.flush();
        jdbc.sql("""
                UPDATE documents SET category_id = :categoryId, grouping_scheme = 'BROAD',
                    assignment_source = 'AI_NEW', assignment_rule_version = 'test'
                WHERE id IN (:ids)
                """)
                .param(
                        "categoryId",
                        category.getId()
                )
                .param(
                        "ids",
                        List.of(
                                first,
                                second
                        )
                )
                .update();
        var parameters = DocumentListParameters.of(
                null,
                1,
                null,
                null
        );

        // when
        var folders = query.findTopics(
                workspaceId,
                memberId,
                parameters
        );
        var cards = query.findPage(
                workspaceId,
                memberId,
                parameters,
                null
        );

        // then
        assertThat(folders).extracting(folder -> folder.topic() + ":" + folder.documentCount())
                .containsExactly("녹음·전사:3");
        assertThat(cards).extracting(DocumentCardResult::id)
                .containsExactly(
                        legacy,
                        second
                );
        assertThat(cards).extracting(DocumentCardResult::topic)
                .containsExactly(
                        "녹음·전사",
                        "녹음·전사"
                );
        assertThat(
                jdbc.sql("SELECT topic FROM documents WHERE id = :id")
                        .param(
                                "id",
                                second
                        )
                        .query(String.class)
                        .single()
        ).isEqualTo("전사 품질");
    }

    @Test
    @DisplayName("큰 주제 개수도 확인 필터 전체 기준이며 빈 주제와 다른 Workspace를 제외한다")
    void find_success_folderFilterAndEmptyCategory() {
        // given
        long documentId = saveDocument(
                workspaceId,
                "안건"
        );
        saveDocument(
                workspaceId,
                "다른 안건"
        );
        saveDocument(
                fixtures.saveWorkspace(),
                "외부"
        );
        var category = com.knot.backend.document.domain.model.DocumentCategory.create(
                workspaceId,
                "테스트 큰 주제",
                "테스트 범위",
                DocumentFixtures.CREATED_AT
        );
        entityManager.persist(category);
        entityManager.persist(
                com.knot.backend.document.domain.model.DocumentCategory.create(
                        workspaceId,
                        "빈 큰 주제",
                        "표시하지 않는 주제",
                        DocumentFixtures.CREATED_AT
                )
        );
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        memberId
                )
        );
        entityManager.flush();
        jdbc.sql("""
                UPDATE documents SET category_id = :categoryId, grouping_scheme = 'BROAD',
                    assignment_source = 'RULE_NEW', assignment_rule_version = 'test'
                WHERE id = :id
                """)
                .param(
                        "categoryId",
                        category.getId()
                )
                .param(
                        "id",
                        documentId
                )
                .update();
        var parameters = DocumentListParameters.of(
                null,
                1,
                MyConfirmationState.PENDING,
                null
        );

        // when
        var folders = query.findTopics(
                workspaceId,
                memberId,
                parameters
        );
        var cards = query.findPage(
                workspaceId,
                memberId,
                parameters,
                null
        );

        // then
        assertThat(folders).extracting(folder -> folder.topic() + ":" + folder.documentCount())
                .containsExactly("테스트 큰 주제:1");
        assertThat(cards).extracting(DocumentCardResult::topic)
                .containsExactly("테스트 큰 주제");
    }

    @Test
    @DisplayName("JPA로 등록한 확인 대상은 주제 필터와 카드 집계에 즉시 반영된다")
    void find_success_pendingJpaConfirmation() {
        long documentId = saveDocument(
                workspaceId,
                "정책"
        );
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        memberId
                )
        );
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                50,
                MyConfirmationState.PENDING,
                null
        );

        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        parameters
                )
        ).extracting(topic -> topic.documentCount())
                .containsExactly(1);
        List<DocumentCardResult> cards = query.findPage(
                workspaceId,
                memberId,
                parameters,
                null
        );
        assertThat(cards).hasSize(1);
        assertThat(
                cards.getFirst()
                        .myConfirmationState()
        ).isEqualTo(MyConfirmationState.PENDING);
        assertThat(
                cards.getFirst()
                        .confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("주제 조회 없이 카드만 읽어도 JPA 확인 대상 저장이 반영된다")
    void findPage_success_pendingJpaConfirmation() {
        long documentId = saveDocument(
                workspaceId,
                "정책"
        );
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        memberId
                )
        );
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                50,
                MyConfirmationState.PENDING,
                null
        );

        List<DocumentCardResult> cards = query.findPage(
                workspaceId,
                memberId,
                parameters,
                null
        );

        assertThat(cards).hasSize(1);
        assertThat(
                cards.getFirst()
                        .id()
        ).isEqualTo(documentId);
        assertThat(
                cards.getFirst()
                        .myConfirmationState()
        ).isEqualTo(MyConfirmationState.PENDING);
        assertThat(
                cards.getFirst()
                        .confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("녹음과 내 확인 상태 필터는 AND로 적용하고 필터 밖 주제는 제외한다")
    void find_success_combinedFilters() {
        long pending = saveDocument(
                workspaceId,
                "정책"
        );
        long confirmed = saveDocument(
                workspaceId,
                "개발"
        );
        long notRequired = saveDocument(
                workspaceId,
                "기타"
        );
        fixtures.target(
                pending,
                memberId,
                null
        );
        fixtures.target(
                confirmed,
                memberId,
                DocumentFixtures.CREATED_AT
        );
        long recordingId = recordingId(pending);
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                50,
                MyConfirmationState.PENDING,
                recordingId
        );

        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        parameters,
                        null
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(pending);
        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        parameters
                )
        ).extracting(topic -> topic.topic())
                .containsExactly("정책");
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                50,
                                MyConfirmationState.CONFIRMED,
                                null
                        ),
                        null
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(confirmed);
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                50,
                                MyConfirmationState.NOT_REQUIRED,
                                null
                        ),
                        null
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(notRequired);
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                50,
                                MyConfirmationState.CONFIRMED,
                                recordingId
                        ),
                        null
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("확인·재가입·탈퇴 대상을 중복 없이 집계하며 읽기는 문서를 변경하지 않는다")
    void find_success_membershipCountsAndReadonly() {
        long document = saveDocument(
                workspaceId,
                "정책"
        );
        fixtures.target(
                document,
                memberId,
                DocumentFixtures.CREATED_AT
        );
        long rejoined = fixtures.saveMember("재가입");
        fixtures.join(
                workspaceId,
                rejoined
        );
        fixtures.target(
                document,
                rejoined,
                null
        );
        fixtures.leave(
                workspaceId,
                rejoined
        );
        fixtures.join(
                workspaceId,
                rejoined
        );
        long excluded = fixtures.saveMember("탈퇴");
        fixtures.join(
                workspaceId,
                excluded
        );
        fixtures.target(
                document,
                excluded,
                null
        );
        fixtures.leave(
                workspaceId,
                excluded
        );
        String before = fixtures.snapshot();

        DocumentCardResult card = query.findPage(
                workspaceId,
                memberId,
                DocumentListParameters.of(
                        null,
                        null,
                        null,
                        null
                ),
                null
        )
                .getFirst();

        assertThat(card.myConfirmationState()).isEqualTo(MyConfirmationState.CONFIRMED);
        assertThat(
                card.confirmationSummary()
                        .confirmedCount()
        ).isEqualTo(1);
        assertThat(
                card.confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(
                card.confirmationSummary()
                        .excludedCount()
        ).isEqualTo(1);
        assertThat(card.recordingDurationSeconds()).isEqualTo(1850);
        assertThat(card.summary()).isNull();
        assertThat(fixtures.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("생성 시각의 마이크로초 순서를 보존하고 보관 문서도 조회한다")
    void find_success_timestampPrecisionAndArchived() {
        long newer = saveDocument(
                workspaceId,
                "최신"
        );
        long older = saveDocument(
                workspaceId,
                "이전"
        );
        Instant time = DocumentFixtures.CREATED_AT.plusNanos(123456000);
        jdbc.sql("UPDATE documents SET created_at = :time WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(time)
                )
                .param(
                        "id",
                        newer
                )
                .update();
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = :time WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(time)
                )
                .param(
                        "id",
                        older
                )
                .update();
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                1,
                null,
                null
        );

        List<DocumentCardResult> first = query.findPage(
                workspaceId,
                memberId,
                parameters,
                null
        );
        assertThat(first).extracting(DocumentCardResult::id)
                .containsExactly(
                        newer,
                        older
                );
        assertThat(
                first.getFirst()
                        .createdAt()
        ).isEqualTo(time);
        assertThat(
                first.getLast()
                        .status()
        ).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        parameters,
                        DocumentCursor.of(
                                workspaceId,
                                memberId,
                                null,
                                null,
                                time,
                                newer
                        )
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(older);
    }

    @Test
    @DisplayName("없는 녹음이나 다른 Workspace 녹음은 빈 목록이며 성공 문서는 다른 Job 실패로 숨기지 않는다")
    void find_success_recordingScopeAndPartialGeneration() {
        long document = saveDocument(
                workspaceId,
                "정책"
        );
        long transcript = jdbc.sql("SELECT source_transcript_id FROM documents WHERE id = :id")
                .param(
                        "id",
                        document
                )
                .query(Long.class)
                .single();
        fixtures.saveJob(
                transcript,
                "FAILED"
        );
        fixtures.saveJob(
                transcript,
                "RUNNING"
        );
        long other = saveDocument(
                fixtures.saveWorkspace(),
                "외부"
        );
        for (long recording : List.of(
                Long.MAX_VALUE,
                recordingId(other)
        )) {
            DocumentListParameters parameters = DocumentListParameters.of(
                    null,
                    null,
                    null,
                    recording
            );
            assertThat(
                    query.findTopics(
                            workspaceId,
                            memberId,
                            parameters
                    )
            ).isEmpty();
            assertThat(
                    query.findPage(
                            workspaceId,
                            memberId,
                            parameters,
                            null
                    )
            ).isEmpty();
        }
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                null,
                                null,
                                null
                        ),
                        null
                )
        ).extracting(DocumentCardResult::id)
                .containsExactly(document);
    }

    @Test
    @DisplayName("미확인 후보가 페이지보다 많아도 모든 카드 값과 커서 순서가 기존 조회와 같다")
    void findPage_success_manyPendingCandidatesAndCursor() {
        // given
        long recording = fixtures.saveRecording(
                workspaceId,
                memberId,
                1850999
        );
        long transcript = fixtures.saveTranscript(recording);
        long otherMember = fixtures.saveMember("다른 조회자");
        List<Long> expectedIds = new ArrayList<>();
        for (int index = 0; index < 168; index++) {
            long document = fixtures.saveDocument(
                    workspaceId,
                    recording,
                    transcript,
                    fixtures.saveJob(
                            transcript,
                            "SUCCEEDED"
                    ),
                    "주제-" + index
            );
            Instant time = DocumentFixtures.CREATED_AT.plusNanos((index / 3) * 1000L);
            jdbc.sql("UPDATE documents SET created_at = :time WHERE id = :id")
                    .param(
                            "time",
                            Timestamp.from(time)
                    )
                    .param(
                            "id",
                            document
                    )
                    .update();
            if (index % 7 == 0) {
                fixtures.target(
                        document,
                        memberId,
                        DocumentFixtures.CREATED_AT
                );
                continue;
            }
            if (index % 7 == 1) {
                fixtures.target(
                        document,
                        otherMember,
                        null
                );
                continue;
            }
            fixtures.target(
                    document,
                    memberId,
                    null
            );
            expectedIds.add(document);
        }
        List<Long> recordingIds = new ArrayList<>(expectedIds);
        Collections.reverse(recordingIds);
        for (int index = 0; index < 8; index++) {
            long otherRecordingDocument = saveDocument(
                    workspaceId,
                    "다른 녹음-" + index
            );
            fixtures.target(
                    otherRecordingDocument,
                    memberId,
                    null
            );
            jdbc.sql("UPDATE documents SET created_at = :time WHERE id = :id")
                    .param(
                            "time",
                            Timestamp.from(DocumentFixtures.CREATED_AT.plusSeconds(1))
                    )
                    .param(
                            "id",
                            otherRecordingDocument
                    )
                    .update();
            expectedIds.add(otherRecordingDocument);
        }
        jdbc.sql("""
                UPDATE documents SET status = 'ARCHIVED', archived_at = created_at, summary = '요약'
                WHERE id = :id
                """)
                .param(
                        "id",
                        recordingIds.getFirst()
                )
                .update();
        long foreignDocument = saveDocument(
                fixtures.saveWorkspace(),
                "외부"
        );
        fixtures.target(
                foreignDocument,
                memberId,
                null
        );
        Collections.reverse(expectedIds);

        // when & then
        assertThat(
                repository.findPendingPage(
                        workspaceId,
                        memberId,
                        null,
                        false,
                        Instant.EPOCH,
                        0,
                        101
                )
        ).containsExactlyElementsOf(
                repository.findPage(
                        workspaceId,
                        memberId,
                        null,
                        "PENDING",
                        false,
                        Instant.EPOCH,
                        0,
                        PageRequest.of(
                                0,
                                101
                        )
                )
        )
                .hasSize(101);
        assertPendingPagesEqual(
                null,
                expectedIds
        );
        assertPendingPagesEqual(
                recording,
                recordingIds
        );
        assertPendingPagesEqual(
                recordingId(foreignDocument),
                List.of()
        );
        assertPendingPagesEqual(
                Long.MAX_VALUE,
                List.of()
        );
        assertThat(
                repository.findPendingPage(
                        workspaceId,
                        otherMember,
                        null,
                        false,
                        Instant.EPOCH,
                        0,
                        101
                )
        ).hasSize(24);
    }

    @Test
    @DisplayName("미확인 주제 집계는 녹음과 회원 범위를 지키고 페이지와 무관하게 전체 건수를 센다")
    void findTopics_success_pendingCountsAndRecordingScope() {
        // given
        long otherMember = fixtures.saveMember("다른 집계 조회자");
        List<String> topics = List.of(
                "alpha",
                "beta",
                "gamma"
        );
        long pendingDocument = 0;
        for (int index = 0; index < 12; index++) {
            long document = saveDocument(
                    workspaceId,
                    topics.get(index % 3)
            );
            if (index % 6 == 0) {
                fixtures.target(
                        document,
                        memberId,
                        DocumentFixtures.CREATED_AT
                );
                continue;
            }
            if (index % 6 == 1) {
                fixtures.target(
                        document,
                        otherMember,
                        null
                );
                continue;
            }
            fixtures.target(
                    document,
                    memberId,
                    null
            );
            pendingDocument = document;
        }
        long foreign = saveDocument(
                fixtures.saveWorkspace(),
                "gamma"
        );
        fixtures.target(
                foreign,
                memberId,
                null
        );
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                1,
                MyConfirmationState.PENDING,
                null
        );

        // when
        List<DocumentTopicResult> actual = query.findTopics(
                workspaceId,
                memberId,
                parameters
        );

        // then
        assertThat(actual).containsExactly(
                new DocumentTopicResult(
                        "alpha",
                        2
                ),
                new DocumentTopicResult(
                        "beta",
                        2
                ),
                new DocumentTopicResult(
                        "gamma",
                        4
                )
        );
        assertThat(
                repository.findPendingTopics(
                        workspaceId,
                        memberId,
                        null
                )
        ).containsExactlyElementsOf(
                repository.findTopics(
                        workspaceId,
                        memberId,
                        null,
                        "PENDING"
                )
        );
        DocumentCursor cursor = DocumentCursor.of(
                workspaceId,
                memberId,
                MyConfirmationState.PENDING,
                null,
                DocumentFixtures.CREATED_AT,
                pendingDocument
        );
        DocumentListParameters nextPage = DocumentListParameters.of(
                cursor.encode(),
                100,
                MyConfirmationState.PENDING,
                null
        );
        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        nextPage
                )
        ).isEqualTo(actual);
        long recording = recordingId(pendingDocument);
        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                1,
                                MyConfirmationState.PENDING,
                                recording
                        )
                )
        ).containsExactly(
                new DocumentTopicResult(
                        "gamma",
                        1
                )
        );
        assertThat(
                repository.findPendingTopics(
                        workspaceId,
                        memberId,
                        recording
                )
        ).containsExactlyElementsOf(
                repository.findTopics(
                        workspaceId,
                        memberId,
                        recording,
                        "PENDING"
                )
        );
        assertThat(
                repository.findPendingTopics(
                        workspaceId,
                        otherMember,
                        null
                )
        ).containsExactly(
                new DocumentTopicRow(
                        "beta",
                        2
                )
        );
        for (long excludedRecording : List.of(
                recordingId(foreign),
                Long.MAX_VALUE
        )) {
            assertThat(
                    query.findTopics(
                            workspaceId,
                            memberId,
                            DocumentListParameters.of(
                                    null,
                                    1,
                                    MyConfirmationState.PENDING,
                                    excludedRecording
                            )
                    )
            ).isEmpty();
        }
        assertThat(
                repository.findPendingTopics(
                        workspaceId,
                        Long.MAX_VALUE,
                        null
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("주제 집계만 읽어도 JPA에 등록한 미확인 대상이 반영된다")
    void findTopics_success_pendingJpaConfirmation() {
        // given
        long document = saveDocument(
                workspaceId,
                "정책"
        );
        entityManager.persist(
                DocumentConfirmation.require(
                        document,
                        memberId
                )
        );

        // when & then
        assertThat(
                query.findTopics(
                        workspaceId,
                        memberId,
                        DocumentListParameters.of(
                                null,
                                1,
                                MyConfirmationState.PENDING,
                                null
                        )
                )
        ).containsExactly(
                new DocumentTopicResult(
                        "정책",
                        1
                )
        );
    }

    private void assertPendingPagesEqual(
            Long recording,
            List<Long> expectedIds
    ) {
        List<Long> actualIds = new ArrayList<>();
        DocumentCursor cursor = null;
        DocumentListParameters parameters = DocumentListParameters.of(
                null,
                20,
                MyConfirmationState.PENDING,
                recording
        );
        for (int page = 0; page <= expectedIds.size() / 20; page++) {
            Instant cursorTime = Instant.EPOCH;
            long cursorId = 0;
            if (cursor != null) {
                cursorTime = cursor.getCreatedAt();
                cursorId = cursor.getDocumentId();
            }
            List<DocumentCardRow> original = repository.findPage(
                    workspaceId,
                    memberId,
                    recording,
                    "PENDING",
                    cursor != null,
                    cursorTime,
                    cursorId,
                    PageRequest.of(
                            0,
                            21
                    )
            );
            List<DocumentCardRow> candidates = repository.findPendingPage(
                    workspaceId,
                    memberId,
                    recording,
                    cursor != null,
                    cursorTime,
                    cursorId,
                    21
            );
            assertThat(candidates).containsExactlyElementsOf(original);
            List<DocumentCardResult> cards = query.findPage(
                    workspaceId,
                    memberId,
                    parameters,
                    cursor
            );
            assertThat(cards).extracting(DocumentCardResult::id)
                    .containsExactlyElementsOf(
                            original.stream()
                                    .map(DocumentCardRow::id)
                                    .toList()
                    );
            List<DocumentCardRow> consumed = candidates.stream()
                    .limit(20)
                    .toList();
            actualIds.addAll(
                    consumed.stream()
                            .map(DocumentCardRow::id)
                            .toList()
            );
            if (consumed.isEmpty()) {
                break;
            }
            DocumentCardRow last = consumed.getLast();
            cursor = DocumentCursor.of(
                    workspaceId,
                    memberId,
                    MyConfirmationState.PENDING,
                    recording,
                    last.createdAt(),
                    last.id()
            );
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    private long saveDocument(
            long workspace,
            String topic
    ) {
        long recording = fixtures.saveRecording(
                workspace,
                memberId,
                1850999
        );
        long transcript = fixtures.saveTranscript(recording);
        return fixtures.saveDocument(
                workspace,
                recording,
                transcript,
                fixtures.saveJob(
                        transcript,
                        "SUCCEEDED"
                ),
                topic
        );
    }

    private long recordingId(long documentId) {
        return jdbc.sql("SELECT recording_session_id FROM documents WHERE id = :id")
                .param(
                        "id",
                        documentId
                )
                .query(Long.class)
                .single();
    }
}
