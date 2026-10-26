package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTopicParallelProgressTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    @DisplayName("재정리 claim은 150초 모델 호출을 포함할 여유가 있다")
    void claim_success_acceptsResponseAfter120Seconds() {
        // given
        DocumentTopicOperation operation = operation();

        // when
        UUID token = operation.claim(NOW);

        // then
        assertThat(
                operation.owns(
                        token,
                        NOW.plusSeconds(150)
                )
        ).isTrue();
        assertThat(operation.getLeaseUntil()).isEqualTo(NOW.plusSeconds(180));
    }

    @Test
    @DisplayName("완료 순서가 다른 배치를 저장해도 같은 claim과 실행 상태를 유지한다")
    void recordBatchProgress_success_keepsClaim() {
        // given
        DocumentTopicOperation operation = operation();
        operation.capture(150);
        UUID token = operation.claim(NOW);

        // when
        operation.recordBatchProgress(
                token,
                75,
                NOW.plusSeconds(120)
        );
        operation.renewClaim(
                token,
                NOW.plusSeconds(140)
        );
        operation.recordBatchProgress(
                token,
                150,
                NOW.plusSeconds(200)
        );

        // then
        assertThat(operation.getProcessedCount()).isEqualTo(150);
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.RUNNING);
        assertThat(operation.getClaimToken()).isEqualTo(token);
        assertThat(operation.getDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    @DisplayName("만료 후 인계된 실행자의 이전 토큰은 진행 수를 변경하지 못한다")
    void recordBatchProgress_failure_staleToken() {
        // given
        DocumentTopicOperation operation = operation();
        operation.capture(150);
        UUID stale = operation.claim(NOW);
        operation.recover(NOW.plusSeconds(181));
        UUID current = operation.claim(NOW.plusSeconds(181));

        // when & then
        assertThatThrownBy(
                () -> operation.recordBatchProgress(
                        stale,
                        75,
                        NOW.plusSeconds(182)
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(operation.getProcessedCount()).isZero();
        assertThat(operation.getClaimToken()).isEqualTo(current);
    }

    @Test
    @DisplayName("취소 뒤의 배치는 lease를 갱신하거나 진행 수를 저장하지 못한다")
    void renewClaim_failure_cancelled() {
        // given
        DocumentTopicOperation operation = operation();
        UUID token = operation.claim(NOW);
        operation.requestCancellation(NOW.plusSeconds(1));

        // when & then
        assertThatThrownBy(
                () -> operation.renewClaim(
                        token,
                        NOW.plusSeconds(2)
                )
        ).isInstanceOf(DocumentException.class);
        assertThat(operation.getProcessedCount()).isZero();
    }

    @Test
    @DisplayName("복원 작업의 기존 90초 lease는 유지한다")
    void claim_success_preservesRestorationLease() {
        // given
        DocumentTopicOperation operation = DocumentTopicOperation.restoration(
                1,
                2,
                UUID.randomUUID(),
                3,
                4,
                NOW
        );

        // when
        operation.claim(NOW);

        // then
        assertThat(operation.getLeaseUntil()).isEqualTo(NOW.plusSeconds(90));
    }

    private DocumentTopicOperation operation() {
        return DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
    }

    @Test
    @DisplayName("대기 종료 경계에서 재시도 시각이 이미 지났으면 즉시 재접수한다")
    void deferBatches_success_alreadyDueRetry() {
        // given
        DocumentTopicOperation operation = operation();
        UUID token = operation.claim(NOW);
        // when
        operation.deferBatches(
                token,
                NOW.plusSeconds(5),
                NOW.plusSeconds(6)
        );
        // then
        assertThat(operation.isReady(NOW.plusSeconds(6))).isTrue();
        assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(6));
    }
}
