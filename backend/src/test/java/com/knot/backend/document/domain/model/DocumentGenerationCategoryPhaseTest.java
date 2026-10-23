package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentGenerationCategoryPhaseTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @Test
    @DisplayName("본문 저장 단계를 완료하지 않은 Job을 배정이나 공개로 재개하지 못한다")
    void resumeGeneration_failure_bodyNotPrepared() {
        // given
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                1,
                2,
                DocumentTopic.of("안건"),
                NOW
        );
        job.startRunning(NOW);

        // when & then
        assertThatThrownBy(
                () -> job.resumeGeneration(
                        NOW,
                        NOW,
                        DocumentGenerationPhase.PUBLISH
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("본문 보관 후 배정 단계로 전환해도 사용자와 작성 자동 재시도 횟수는 유지한다")
    void advancePhase_success_preservesRetryQuota() {
        // given
        DocumentGenerationJob job = runningJob();

        // when
        job.beginCategoryAssignment(
                NOW,
                NOW.plusSeconds(45)
        );
        job.resumeGeneration(
                NOW,
                NOW,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );

        // then
        assertThat(job.getProcessingPhase()).isEqualTo(DocumentGenerationPhase.ASSIGN_CATEGORY);
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getAutomaticRetryCount()).isZero();
        assertThat(job.getUserRetryCount()).isZero();
        assertThatThrownBy(() -> job.validateRunningAttempt(1)).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("배정 호출 예산은 재시작하거나 다시 실행해도 초기화되지 않는다")
    void reserveCategoryAttempt_success_preservedBudget() {
        // given
        DocumentGenerationJob job = runningJob();
        job.beginCategoryAssignment(
                NOW,
                NOW.plusSeconds(45)
        );

        // when
        job.reserveCategoryAttempt(NOW);
        job.resumeGeneration(
                NOW,
                NOW,
                DocumentGenerationPhase.ASSIGN_CATEGORY
        );
        job.startRunning(NOW);
        job.reserveCategoryAttempt(NOW.plusSeconds(1));

        // then
        assertThat(job.getCategoryAttemptCount()).isEqualTo(2);
        assertThat(job.getCategoryDeadlineAt()).isEqualTo(NOW.plusSeconds(45));
    }

    private DocumentGenerationJob runningJob() {
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                1,
                2,
                DocumentTopic.of("안건"),
                NOW
        );
        job.startRunning(NOW);
        return job;
    }
}
