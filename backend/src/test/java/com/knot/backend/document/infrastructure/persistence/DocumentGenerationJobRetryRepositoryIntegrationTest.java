package com.knot.backend.document.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.document.application.DocumentGenerationInputQuery;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@Import({TestcontainersConfiguration.class, DocumentGenerationJobRepositoryAdapter.class,
        DocumentGenerationInputQueryAdapter.class})
class DocumentGenerationJobRetryRepositoryIntegrationTest {

    private static final Instant NOW = DocumentFixtures.CREATED_AT.plusSeconds(120);

    @Autowired
    private DocumentGenerationJobRepository jobs;
    @Autowired
    private DocumentGenerationInputQuery inputs;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private long workspaceId;
    private long transcriptId;
    private long jobId;

    @BeforeEach
    void setUp() {
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long memberId = fixtures.saveMember("재시도 멤버");
        workspaceId = fixtures.saveWorkspace();
        long recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                120000
        );
        transcriptId = fixtures.saveTranscript(recordingId);
        jobId = fixtures.saveJob(
                transcriptId,
                "FAILED"
        );
    }

    @Test
    @DisplayName("잠금 조회한 Job의 재시도 상태와 횟수를 저장한다")
    void retry_success_persistAttempt() {
        // given
        DocumentGenerationJob job = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        )
                .orElseThrow();

        // when
        job.retryByUser(NOW);
        jobs.flush();
        entityManager.clear();

        // then
        DocumentGenerationJob stored = jobs.findByWorkspaceIdAndIdForUpdate(
                workspaceId,
                jobId
        )
                .orElseThrow();
        assertThat(stored.getAttemptCount()).isEqualTo(2);
        assertThat(stored.getUserRetryCount()).isEqualTo(1);
        assertThat(stored.getAutomaticRetryCount()).isZero();
        assertThat(stored.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
    }

    @Test
    @DisplayName("다른 Workspace와 없는 Job은 잠금 조회 결과가 없다")
    void findByWorkspaceIdAndIdForUpdate_failure_scope() {
        // when & then
        assertThat(
                jobs.findByWorkspaceIdAndIdForUpdate(
                        workspaceId + 100,
                        jobId
                )
        ).isEmpty();
        assertThat(
                jobs.findByWorkspaceIdAndIdForUpdate(
                        workspaceId,
                        jobId + 100
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("입력 원문도 지정 Workspace 안에서 잠금 조회한다")
    void findInputForUpdate_success() {
        // when
        String content = inputs.findForUpdate(
                workspaceId,
                transcriptId
        )
                .orElseThrow()
                .content();

        // then
        assertThat(content).isEqualTo("전체 녹음 원문");
        assertThat(
                inputs.findForUpdate(
                        workspaceId + 100,
                        transcriptId
                )
        ).isEmpty();
        assertThat(
                inputs.findForUpdate(
                        workspaceId,
                        transcriptId + 100
                )
        ).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt_count = 0", "user_retry_count = -1", "user_retry_count = 4",
            "automatic_retry_count = -1", "attempt_count = 5",
            "attempt_count = 2147483647, automatic_retry_count = 2147483647"})
    @DisplayName("횟수 범위와 전체·사용자·자동 시도 합계는 CHECK로 보장한다")
    void updateCounts_failure_constraint(String assignment) {
        // when & then
        assertThatThrownBy(
                () -> jdbc.sql("UPDATE document_generation_jobs SET " + assignment + " WHERE id = :id")
                        .param(
                                "id",
                                jobId
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사용자 재시도 횟수는 3회를 넘어도 전체 시도 합계와 맞으면 저장한다")
    void updateCounts_success_userRetriesBeyondThree() {
        // when
        int updated = jdbc
                .sql("UPDATE document_generation_jobs SET user_retry_count = 4, attempt_count = 5 WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();

        // then
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("별도 실행 접수 테이블로 Job 정리 순서를 고정하지 않는다")
    void deleteJob_success_withoutExecutionRequestReference() {
        // when
        int deleted = jdbc.sql("DELETE FROM document_generation_jobs WHERE id = :id")
                .param(
                        "id",
                        jobId
                )
                .update();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT count(*) FROM transcripts WHERE id = :id")
                        .param(
                                "id",
                                transcriptId
                        )
                        .query(Long.class)
                        .single()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT to_regclass('document_generation_execution_requests') IS NULL")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
    }

    @Test
    @DisplayName("Job이 참조하는 원문은 원문만 단독 삭제할 수 없다")
    void deleteTranscript_failure_jobReference() {
        // when & then
        assertThatThrownBy(
                () -> jdbc.sql("DELETE FROM transcripts WHERE id = :id")
                        .param(
                                "id",
                                transcriptId
                        )
                        .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
