package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentDetailQuery;
import com.knot.backend.document.application.dto.result.DocumentDetailResult;
import com.knot.backend.document.domain.model.Document;
import com.knot.backend.document.domain.model.DocumentConfirmation;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.recording.domain.model.Transcript;
import com.knot.backend.testsupport.SharedPostgresDocumentQueryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
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
class DocumentDetailQueryIntegrationTest extends SharedPostgresDocumentQueryTestSupport {
    @Autowired
    private DocumentDetailQuery query;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long recordingId;
    private long transcriptId;
    private long documentId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        workspaceId = fixtures.saveWorkspace();
        memberId = fixtures.saveMember("작성자");
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                1850999
        );
        transcriptId = fixtures.saveTranscript(recordingId);
        long jobId = fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        documentId = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcriptId,
                jobId,
                "운영 정책"
        );
    }

    @Test
    @DisplayName("DRAFT 본문과 원본 연결을 읽고 확인 대상이 없으면 0명으로 집계한다")
    void find_success_draftWithEmptyTargets() {
        // when
        DocumentDetailResult result = find(memberId);

        // then
        assertThat(result.id()).isEqualTo(documentId);
        assertThat(result.recordingSessionId()).isEqualTo(recordingId);
        assertThat(result.sourceTranscriptId()).isEqualTo(transcriptId);
        assertThat(result.recordingDurationSeconds()).isEqualTo(1850);
        assertThat(result.summary()).isNull();
        assertThat(result.archivedAt()).isNull();
        assertThat(result.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(result.myConfirmationState()).isEqualTo(MyConfirmationState.NOT_REQUIRED);
        assertThat(
                result.confirmationSummary()
                        .confirmedCount()
        ).isZero();
        assertThat(
                result.confirmationSummary()
                        .pendingCount()
        ).isZero();
        assertThat(
                result.confirmationSummary()
                        .excludedCount()
        ).isZero();
    }

    @Test
    @DisplayName("JPA로 등록한 확인 대상은 조회 전에 자동 반영되어 상세 집계에 포함된다")
    void find_success_pendingJpaConfirmation() {
        // given
        entityManager.persist(
                DocumentConfirmation.require(
                        documentId,
                        memberId
                )
        );

        // when
        DocumentDetailResult result = find(memberId);

        // then
        assertThat(result.myConfirmationState()).isEqualTo(MyConfirmationState.PENDING);
        assertThat(
                result.confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("가입·탈퇴·재가입 이력은 고정 대상을 중복 없이 집계한다")
    void find_success_confirmationMembershipHistory() {
        // given
        fixtures.target(
                documentId,
                memberId,
                DocumentFixtures.CREATED_AT.plusSeconds(30)
        );
        fixtures.leave(
                workspaceId,
                memberId
        );
        long rejoinedId = fixtures.saveMember("재가입자");
        fixtures.join(
                workspaceId,
                rejoinedId
        );
        fixtures.target(
                documentId,
                rejoinedId,
                null
        );
        fixtures.leave(
                workspaceId,
                rejoinedId
        );
        fixtures.join(
                workspaceId,
                rejoinedId
        );
        long excludedId = fixtures.saveMember("탈퇴자");
        fixtures.join(
                workspaceId,
                excludedId
        );
        fixtures.target(
                documentId,
                excludedId,
                null
        );
        fixtures.leave(
                workspaceId,
                excludedId
        );
        long newcomerId = fixtures.saveMember("새 가입자");
        fixtures.join(
                workspaceId,
                newcomerId
        );

        // when
        DocumentDetailResult result = find(newcomerId);

        // then
        assertThat(
                result.confirmationSummary()
                        .confirmedCount()
        ).isEqualTo(1);
        assertThat(
                result.confirmationSummary()
                        .pendingCount()
        ).isEqualTo(1);
        assertThat(
                result.confirmationSummary()
                        .excludedCount()
        ).isEqualTo(1);
        assertThat(result.myConfirmationState()).isEqualTo(MyConfirmationState.NOT_REQUIRED);
        assertThat(find(rejoinedId).myConfirmationState()).isEqualTo(MyConfirmationState.PENDING);
    }

    @Test
    @DisplayName("확인 완료 대상은 CONFIRMED로 읽고 보관 상태를 변경하지 않는다")
    void find_success_archivedConfirmed() {
        // given
        fixtures.target(
                documentId,
                memberId,
                DocumentFixtures.CREATED_AT.plusSeconds(30)
        );
        jdbc.sql("UPDATE documents SET status = 'ARCHIVED', archived_at = :time, summary = '요약' WHERE id = :id")
                .param(
                        "time",
                        Timestamp.from(DocumentFixtures.CREATED_AT.plusSeconds(30))
                )
                .param(
                        "id",
                        documentId
                )
                .update();
        String before = fixtures.snapshot();

        // when
        DocumentDetailResult result = find(memberId);

        // then
        assertThat(result.status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(result.archivedAt()).isEqualTo(DocumentFixtures.CREATED_AT.plusSeconds(30));
        assertThat(result.summary()).isEqualTo("요약");
        assertThat(result.myConfirmationState()).isEqualTo(MyConfirmationState.CONFIRMED);
        assertThat(fixtures.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 주제의 실패·실행 중 Job은 성공 문서를 숨기지 않는다")
    void find_success_partialGeneration() {
        // given
        fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        fixtures.saveJob(
                transcriptId,
                "RUNNING"
        );

        // when & then
        assertThat(find(memberId).id()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("문서가 없으면 빈 조회 결과를 반환한다")
    void find_failure_missingDocument() {
        // when & then
        assertThat(
                query.find(
                        workspaceId,
                        Long.MAX_VALUE,
                        memberId
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 Workspace의 문서는 조회하지 않는다")
    void find_failure_otherWorkspace() {
        // given
        long otherWorkspace = fixtures.saveWorkspace();

        // when & then
        assertThat(
                query.find(
                        otherWorkspace,
                        documentId,
                        memberId
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("1초 미만 녹음 길이는 0초로 반환한다")
    void find_success_subsecondDuration() {
        // given
        jdbc.sql("UPDATE recording_sessions SET accumulated_recording_millis = 999 WHERE id = :id")
                .param(
                        "id",
                        recordingId
                )
                .update();

        // when & then
        assertThat(find(memberId).recordingDurationSeconds()).isZero();
    }

    @Test
    @DisplayName("JPA로 저장한 원문·Job·Document·확인 대상을 다시 읽는다")
    void find_success_jpaMapping() {
        // given
        long jpaRecordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        Transcript transcript = Transcript.create(
                jpaRecordingId,
                "JPA 저장 원문",
                DocumentFixtures.CREATED_AT
        );
        entityManager.persist(transcript);
        long batchId = fixtures.saveGenerationBatch(transcript.getId());
        fixtures.saveRegisteredTopic(
                batchId,
                "개발 정책"
        );
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                batchId,
                transcript.getId(),
                DocumentTopic.of("개발 정책"),
                DocumentFixtures.CREATED_AT
        );
        entityManager.persist(job);
        Document document = Document.createDraft(
                workspaceId,
                jpaRecordingId,
                transcript.getId(),
                job.getId(),
                "개발 정책",
                "JPA 제목",
                null,
                "본문",
                DocumentFixtures.CREATED_AT
        );
        entityManager.persist(document);
        entityManager.persist(
                DocumentConfirmation.require(
                        document.getId(),
                        memberId
                )
        );
        entityManager.flush();
        entityManager.clear();

        // when
        Document loaded = entityManager.find(
                Document.class,
                document.getId()
        );

        // then
        assertThat(loaded.getContent()).isEqualTo("본문");
        assertThat(
                DocumentDetailResult.from(
                        query.find(
                                workspaceId,
                                document.getId(),
                                memberId
                        )
                                .orElseThrow()
                )
                        .myConfirmationState()
        ).isEqualTo(MyConfirmationState.PENDING);
        assertThat(
                entityManager.find(
                        Transcript.class,
                        transcript.getId()
                )
                        .getRawTranscriptJson()
        ).isEqualTo("JPA 저장 원문");
        assertThat(
                entityManager.find(
                        DocumentGenerationJob.class,
                        job.getId()
                )
                        .getTranscriptId()
        ).isEqualTo(transcript.getId());
    }

    private DocumentDetailResult find(long currentMemberId) {
        return DocumentDetailResult.from(
                query.find(
                        workspaceId,
                        documentId,
                        currentMemberId
                )
                        .orElseThrow()
        );
    }
}
