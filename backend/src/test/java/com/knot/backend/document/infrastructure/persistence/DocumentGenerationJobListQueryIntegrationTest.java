package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationJobListQuery;
import com.knot.backend.document.application.dto.result.DocumentGenerationJobItemResult;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationJobCursor;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({SharedPostgresTestConfiguration.class, DocumentConfirmationQueryAdapter.class,
        DocumentDetailQueryAdapter.class, DocumentGenerationJobListQueryAdapter.class, DocumentListQueryAdapter.class})
class DocumentGenerationJobListQueryIntegrationTest extends SharedPostgresDocumentQueryTestSupport {
    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(8 * 24 * 60 * 60);

    @Autowired
    private DocumentGenerationJobListQuery query;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private DocumentFixtures fixtures;
    private long memberId;
    private long workspaceId;
    private long recordingId;
    private long transcriptId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("작업 작성자");
        workspaceId = fixtures.saveWorkspace();
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        transcriptId = fixtures.saveTranscript(recordingId);
    }

    @Test
    @DisplayName("같은 녹음의 진행·실패 작업을 유지하고 성공·다른 Workspace 작업은 제외한다")
    void findPage_success_statusAndWorkspaceScope() {
        // given
        long queued = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        long running = fixtures.saveJob(
                transcriptId,
                "RUNNING"
        );
        long failed = fixtures.saveJob(
                transcriptId,
                "FAILED",
                DocumentFixtures.CREATED_AT,
                NOW.minusSeconds(60)
        );
        fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        long foreignRecording = fixtures.saveRecording(
                fixtures.saveWorkspace(),
                memberId,
                1000
        );
        fixtures.saveJob(
                fixtures.saveTranscript(foreignRecording),
                "QUEUED"
        );

        // when
        List<DocumentGenerationJobItemResult> items = query.findPage(
                workspaceId,
                memberId,
                101,
                null,
                NOW
        );

        // then
        assertThat(items).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(
                        failed,
                        running,
                        queued
                );
        assertThat(items).extracting(DocumentGenerationJobItemResult::recordingSessionId)
                .containsOnly(recordingId);
    }

    @Test
    @DisplayName("실패 기한 직전만 포함하고 정확한 경계와 이후는 제외한다")
    void findPage_success_failureDeadlineBoundary() {
        // given
        Instant boundary = NOW.minusSeconds(7 * 24 * 60 * 60);
        fixtures.saveJob(
                transcriptId,
                "FAILED",
                DocumentFixtures.CREATED_AT,
                boundary.minusNanos(1000)
        );
        fixtures.saveJob(
                transcriptId,
                "FAILED",
                DocumentFixtures.CREATED_AT,
                boundary
        );
        long visible = fixtures.saveJob(
                transcriptId,
                "FAILED",
                DocumentFixtures.CREATED_AT,
                boundary.plusNanos(1000)
        );

        // when
        List<DocumentGenerationJobItemResult> items = query.findPage(
                workspaceId,
                memberId,
                20,
                null,
                NOW
        );

        // then
        assertThat(items).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(visible);
    }

    @Test
    @DisplayName("같은 생성 시각은 ID 내림차순이며 삭제된 경계 작업 없이도 이어 조회한다")
    void findPage_success_cursorAndLimit() {
        // given
        long oldest = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        long middle = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        long newest = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        List<DocumentGenerationJobItemResult> first = query.findPage(
                workspaceId,
                memberId,
                2,
                null,
                NOW
        );
        DocumentGenerationJobCursor cursor = DocumentGenerationJobCursor.of(
                workspaceId,
                memberId,
                first.getLast()
                        .createdAt(),
                middle
        );
        jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        middle
                )
                .update();

        // when
        List<DocumentGenerationJobItemResult> second = query.findPage(
                workspaceId,
                memberId,
                2,
                cursor,
                NOW
        );

        // then
        assertThat(first).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(
                        newest,
                        middle
                );
        assertThat(second).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(oldest);
    }

    @Test
    @DisplayName("JPA로 추가한 작업은 조회에 자동 반영한다")
    void findPage_success_jpaFlush() {
        // given
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                fixtures.saveGenerationBatch(transcriptId),
                transcriptId,
                NOW
        );
        entityManager.persist(job);

        // when
        List<DocumentGenerationJobItemResult> items = query.findPage(
                workspaceId,
                memberId,
                20,
                null,
                NOW
        );

        // then
        assertThat(items).hasSize(1);
        assertThat(
                items.getFirst()
                        .jobId()
        ).isEqualTo(job.getId());
        assertThat(
                items.getFirst()
                        .status()
        ).isEqualTo(DocumentGenerationJobStatus.QUEUED);
    }

    @Test
    @DisplayName("같은 Workspace의 다른 멤버 작업은 필터링한 뒤 페이지 크기를 적용한다")
    void findPage_success_recordingOwnerScope() {
        // given
        long ownJob = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );
        long teammateRecording = fixtures.saveRecording(
                workspaceId,
                fixtures.saveMember("다른 작성자"),
                1000
        );
        long teammateTranscript = fixtures.saveTranscript(teammateRecording);
        fixtures.saveJob(
                teammateTranscript,
                "QUEUED"
        );
        fixtures.saveJob(
                teammateTranscript,
                "RUNNING"
        );
        fixtures.saveJob(
                teammateTranscript,
                "FAILED",
                DocumentFixtures.CREATED_AT,
                NOW
        );

        // when
        List<DocumentGenerationJobItemResult> items = query.findPage(
                workspaceId,
                memberId,
                1,
                null,
                NOW
        );

        // then
        assertThat(items).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(ownJob);
    }

    @Test
    @DisplayName("문서 작업 조회를 위한 녹음 제목 컬럼을 추가하지 않는다")
    void recordingSchema_success_noTitleColumn() {
        // when
        long titleColumns = jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'recording_sessions' AND column_name = 'title'
                """)
                .query(Long.class)
                .single();

        // then
        assertThat(titleColumns).isZero();
    }

    @Test
    @DisplayName("재실패는 기한을 갱신하며 재접수된 진행 작업은 과거 실패 기한으로 숨기지 않는다")
    void findPage_success_refailureAndActiveRetry() {
        // given
        long failedId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        long runningId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
        jdbc.sql(
                "UPDATE document_generation_jobs SET status = 'RUNNING', execution_deadline_at = updated_at + INTERVAL '150 seconds', failure_cause = NULL WHERE id = :id"
        )
                .param(
                        "id",
                        runningId
                )
                .update();
        DocumentGenerationJob failed = entityManager.find(
                DocumentGenerationJob.class,
                failedId
        );
        failed.recordFailure(NOW);

        // when
        List<DocumentGenerationJobItemResult> items = query.findPage(
                workspaceId,
                memberId,
                20,
                null,
                NOW
        );

        // then
        assertThat(items).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(
                        runningId,
                        failedId
                );
        assertThat(failed.getExpiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
    }

    @Test
    @DisplayName("성공한 작업은 실패 기록으로 변경하지 않는다")
    void recordFailure_failure_succeededJob() {
        // given
        long id = fixtures.saveJob(
                transcriptId,
                "SUCCEEDED"
        );
        DocumentGenerationJob job = entityManager.find(
                DocumentGenerationJob.class,
                id
        );

        // when & then
        assertThatThrownBy(() -> job.recordFailure(NOW)).isInstanceOf(DocumentException.class);
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("DB는 실패 시각 없는 FAILED 저장을 거절한다")
    void save_failure_missingFailureTime() {
        // given
        long id = fixtures.saveJob(
                transcriptId,
                "QUEUED"
        );

        // when & then
        assertThatThrownBy(
                () -> jdbc.sql("UPDATE document_generation_jobs SET status = 'FAILED' WHERE id = :id")
                        .param(
                                "id",
                                id
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB는 실패 시각과 168시간이 다른 만료 시각을 거절한다")
    void save_failure_invalidDeadline() {
        // given
        long id = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );

        // when & then
        assertThatThrownBy(
                () -> jdbc.sql("UPDATE document_generation_jobs SET expires_at = :time WHERE id = :id")
                        .param(
                                "time",
                                Timestamp.from(NOW)
                        )
                        .param(
                                "id",
                                id
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("조회 결과가 없으면 null 대신 빈 목록을 반환한다")
    void findPage_success_empty() {
        // when & then
        assertThat(
                query.findPage(
                        workspaceId,
                        memberId,
                        20,
                        null,
                        NOW
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("갱신 시각·ID와 관계없이 생성 시각으로 정렬하고 다른 시각 경계도 이어 조회한다")
    void findPage_success_createdAtOrdering() {
        // given
        long newest = fixtures.saveJob(
                transcriptId,
                "QUEUED",
                NOW.minusSeconds(60),
                null
        );
        long oldest = fixtures.saveJob(
                transcriptId,
                "RUNNING"
        );
        jdbc.sql(
                "UPDATE document_generation_jobs SET updated_at = :time, execution_deadline_at = CAST(:time AS timestamptz) + INTERVAL '150 seconds' WHERE id = :id"
        )
                .param(
                        "time",
                        Timestamp.from(NOW)
                )
                .param(
                        "id",
                        oldest
                )
                .update();
        DocumentGenerationJobCursor cursor = DocumentGenerationJobCursor.of(
                workspaceId,
                memberId,
                NOW.minusSeconds(60),
                newest
        );

        // when
        List<DocumentGenerationJobItemResult> first = query.findPage(
                workspaceId,
                memberId,
                1,
                null,
                NOW
        );
        List<DocumentGenerationJobItemResult> second = query.findPage(
                workspaceId,
                memberId,
                1,
                cursor,
                NOW
        );

        // then
        assertThat(first).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(newest);
        assertThat(second).extracting(DocumentGenerationJobItemResult::jobId)
                .containsExactly(oldest);
    }
}
