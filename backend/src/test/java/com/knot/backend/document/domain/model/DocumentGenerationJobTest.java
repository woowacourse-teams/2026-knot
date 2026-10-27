package com.knot.backend.document.domain.model;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentGenerationJobTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T00:00:00Z");
    private static final Instant FAILED_AT = CREATED_AT.plusSeconds(60);

    @Test
    void renewsOnlyCurrentUnexpiredRunningAttempt() {
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                1,
                1,
                DocumentTopic.of("검색"),
                CREATED_AT
        );
        job.startRunning(
                CREATED_AT,
                CREATED_AT.plusSeconds(150)
        );
        job.renewExecutionLease(
                1,
                CREATED_AT.plusSeconds(100),
                CREATED_AT.plusSeconds(250)
        );
        assertThat(job.getExecutionDeadlineAt()).isEqualTo(CREATED_AT.plusSeconds(250));
        assertThatThrownBy(
                () -> job.renewExecutionLease(
                        2,
                        CREATED_AT.plusSeconds(101),
                        CREATED_AT.plusSeconds(251)
                )
        ).isInstanceOf(DocumentException.class);
        assertThatThrownBy(
                () -> job.renewExecutionLease(
                        1,
                        CREATED_AT.plusSeconds(250),
                        CREATED_AT.plusSeconds(400)
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(job.getExecutionDeadlineAt()).isEqualTo(CREATED_AT.plusSeconds(250));
    }

    @Test
    @DisplayName("문서 저장은 GENERATION 단계에서만 허용한다")
    void validateGenerationStage_success() {
        // given
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                1,
                1,
                DocumentTopic.of("검색"),
                CREATED_AT
        );
        // when
        job.validateGenerationStage();
        // then
        assertThat(job.getStage()).isEqualTo(DocumentGenerationJobStage.GENERATION);
    }

    @Test
    @DisplayName("분류 Job은 문서 저장 단계로 사용할 수 없다")
    void validateGenerationStage_failure_classification() {
        // given
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                CREATED_AT
        );
        // when & then
        assertThatThrownBy(job::validateGenerationStage).isInstanceOf(DocumentException.class)
                .hasMessage(DocumentErrorCode.GENERATION_REGISTRATION_CONFLICT.getMessage());
    }

    @Test
    @DisplayName("첫 접수는 최초 시도 1회이며 사용자·자동 재시도는 0회다")
    void queue_success_initialAttemptCounts() {
        // when
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                CREATED_AT
        );

        // then
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getUserRetryCount()).isZero();
        assertThat(job.getAutomaticRetryCount()).isZero();
    }

    @Test
    @DisplayName("실패 Job은 같은 원문·생성 시각을 유지하고 사용자 재시도를 접수한다")
    void retryByUser_success() {
        // given
        DocumentGenerationJob job = failedJob();
        Instant acceptedAt = FAILED_AT.plusSeconds(1);

        // when
        job.retryByUser(acceptedAt);

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getTranscriptId()).isEqualTo(1);
        assertThat(job.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(job.getUpdatedAt()).isEqualTo(acceptedAt);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getUserRetryCount()).isEqualTo(1);
        assertThat(job.getLastFailedAt()).isEqualTo(FAILED_AT);
        assertThat(job.getExpiresAt()).isEqualTo(FAILED_AT.plusSeconds(168 * 3600));
    }

    @Test
    @DisplayName("사용자 재시도 횟수 제한 없이 네 번째 재시도도 접수하고 횟수는 기록용으로 계속 센다")
    void retryByUser_success_fourthRetry() {
        // given
        DocumentGenerationJob job = failedJob();
        for (int retry = 1; retry <= 3; retry++) {
            job.retryByUser(FAILED_AT.plusSeconds(retry * 2));
            job.recordFailure(FAILED_AT.plusSeconds(retry * 2 + 1));
        }
        Instant lastFailure = job.getLastFailedAt();

        // when
        job.retryByUser(lastFailure.plusSeconds(1));

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getUserRetryCount()).isEqualTo(4);
        assertThat(job.getAttemptCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("자동 재시도 이력은 전체 횟수에 포함되지만 사용자 한도를 차감하지 않는다")
    void retryByUser_success_separateAutomaticAttempts() {
        // given
        DocumentGenerationJob job = failedJob();
        ReflectionTestUtils.setField(
                job,
                "automaticRetryCount",
                5
        );
        ReflectionTestUtils.setField(
                job,
                "attemptCount",
                6
        );

        // when
        job.retryByUser(FAILED_AT.plusSeconds(1));

        // then
        assertThat(job.getAutomaticRetryCount()).isEqualTo(5);
        assertThat(job.getUserRetryCount()).isEqualTo(1);
        assertThat(job.getAttemptCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("마지막 실패 뒤 홈 표시 기한인 168시간이 지나도 사용자 재시도를 접수한다")
    void retryByUser_success_afterHomeDisplayPeriod() {
        // given
        DocumentGenerationJob job = failedJob();
        Instant afterDisplayPeriod = job.getExpiresAt()
                .plus(Duration.ofDays(365));

        // when
        job.retryByUser(afterDisplayPeriod);

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getUserRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("표시 기한이 없는 과거 실패 Job도 사용자 재시도를 접수한다")
    void retryByUser_success_withoutDisplayDeadline() {
        // given
        DocumentGenerationJob job = failedJob();
        ReflectionTestUtils.setField(
                job,
                "expiresAt",
                null
        );

        // when
        job.retryByUser(FAILED_AT.plusSeconds(1));

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationJobStatus.class, names = {"QUEUED", "RUNNING", "SUCCEEDED"})
    @DisplayName("실패 이력이 있어도 현재 FAILED가 아니면 재시도하지 않는다")
    void retryByUser_failure_notFailed(DocumentGenerationJobStatus status) {
        // given
        DocumentGenerationJob job = failedJob();
        ReflectionTestUtils.setField(
                job,
                "status",
                status
        );

        // when & then
        assertRetryDenied(
                job,
                FAILED_AT.plusSeconds(1)
        );
    }

    @Test
    @DisplayName("누락·과거·범위 밖 접수 시각은 횟수를 변경하지 않는다")
    void retryByUser_failure_invalidTime() {
        // given
        DocumentGenerationJob job = failedJob();

        // when & then
        assertRetryDenied(
                job,
                null
        );
        assertRetryDenied(
                job,
                FAILED_AT.minusSeconds(1)
        );
        assertRetryDenied(
                job,
                Instant.MAX
        );
    }

    @Test
    @DisplayName("전체 시도 횟수의 정수 범위를 넘는 접수는 상태 변경 전에 거절한다")
    void retryByUser_failure_attemptOverflow() {
        // given
        DocumentGenerationJob job = failedJob();
        ReflectionTestUtils.setField(
                job,
                "attemptCount",
                Integer.MAX_VALUE
        );
        ReflectionTestUtils.setField(
                job,
                "automaticRetryCount",
                Integer.MAX_VALUE - 1
        );

        // when & then
        assertRetryDenied(
                job,
                FAILED_AT.plusSeconds(1)
        );
    }

    private DocumentGenerationJob failedJob() {
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                CREATED_AT
        );
        job.recordFailure(FAILED_AT);
        return job;
    }

    private void assertRetryDenied(
            DocumentGenerationJob job,
            Instant acceptedAt
    ) {
        DocumentGenerationJobStatus status = job.getStatus();
        Instant updatedAt = job.getUpdatedAt();
        int attempts = job.getAttemptCount();
        int retries = job.getUserRetryCount();
        assertThatThrownBy(() -> job.retryByUser(acceptedAt)).isInstanceOf(DocumentException.class)
                .hasMessage(DocumentErrorCode.RETRY_NOT_ALLOWED.getMessage());
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(job.getAttemptCount()).isEqualTo(attempts);
        assertThat(job.getUserRetryCount()).isEqualTo(retries);
    }

    @Test
    @DisplayName("실패 기록은 마지막 실패 시각과 168시간 홈 표시 기한을 저장한다")
    void recordFailure_success() {
        // given
        Instant createdAt = Instant.parse("2026-10-06T00:00:00Z");
        Instant failedAt = createdAt.plusSeconds(60);
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                createdAt
        );

        // when
        job.recordFailure(failedAt);

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.FAILED);
        assertThat(job.getLastFailedAt()).isEqualTo(failedAt);
        assertThat(job.getUpdatedAt()).isEqualTo(failedAt);
        assertThat(job.getExpiresAt()).isEqualTo(failedAt.plusSeconds(7 * 24 * 60 * 60));
    }

    @Test
    @DisplayName("새로운 실패가 기록되면 마지막 실패 기준으로 표시 기한을 갱신한다")
    void recordFailure_success_renewDeadline() {
        // given
        Instant createdAt = Instant.parse("2026-10-06T00:00:00Z");
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                createdAt
        );
        job.recordFailure(createdAt.plusSeconds(60));
        Instant failedAt = createdAt.plusSeconds(120);

        // when
        job.recordFailure(failedAt);

        // then
        assertThat(job.getLastFailedAt()).isEqualTo(failedAt);
        assertThat(job.getExpiresAt()).isEqualTo(failedAt.plusSeconds(7 * 24 * 60 * 60));
    }

    @Test
    @DisplayName("과거·누락·범위 밖 실패 시각은 상태를 변경하지 않는다")
    void recordFailure_failure_invalidTime() {
        // given
        Instant createdAt = Instant.parse("2026-10-06T00:00:00Z");
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                createdAt
        );

        // when & then
        assertThatThrownBy(() -> job.recordFailure(createdAt.minusSeconds(1))).isInstanceOf(DocumentException.class);
        assertThatThrownBy(() -> job.recordFailure(null)).isInstanceOf(DocumentException.class);
        assertThatThrownBy(() -> job.recordFailure(Instant.MAX)).isInstanceOf(DocumentException.class);
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getLastFailedAt()).isNull();
    }

    @Test
    @DisplayName("Job은 입력 원문과 QUEUED 상태로 생성한다")
    void queue_success() {
        // given
        Instant time = Instant.parse("2026-10-06T00:00:00Z");

        // when
        DocumentGenerationJob job = DocumentGenerationJob.queueClassification(
                1,
                1,
                time
        );

        // then
        assertThat(job.getStatus()).isEqualTo(DocumentGenerationJobStatus.QUEUED);
        assertThat(job.getUpdatedAt()).isEqualTo(time);
    }

    @Test
    @DisplayName("입력 원문 ID가 잘못된 Job은 생성하지 못한다")
    void queue_failure_invalidTranscript() {
        // when & then
        assertThatThrownBy(
                () -> DocumentGenerationJob.queueClassification(
                        1,
                        0,
                        Instant.now()
                )
        ).isInstanceOf(DocumentException.class);
    }
}
