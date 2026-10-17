package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentListQuery;
import com.knot.backend.document.application.dto.query.DocumentListParameters;
import com.knot.backend.document.application.dto.result.DocumentCardResult;
import com.knot.backend.document.domain.model.DocumentCursor;
import com.knot.backend.document.domain.model.DocumentConfirmation;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.testsupport.SharedPostgresDocumentQueryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
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
@Import({SharedPostgresTestConfiguration.class, DocumentConfirmationQueryAdapter.class,
        DocumentDetailQueryAdapter.class, DocumentGenerationJobListQueryAdapter.class, DocumentListQueryAdapter.class})
class DocumentListQueryIntegrationTest extends SharedPostgresDocumentQueryTestSupport {
    @Autowired
    private DocumentListQuery query;
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
