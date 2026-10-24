package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.domain.DocumentException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTopicCancellationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    @DisplayName("대기 작업의 취소는 접수 시각과 완료 시각을 각각 보존한다")
    void requestCancellation_success_queuedAndReplay() {
        // given
        DocumentTopicOperation operation = operation();
        // when
        operation.requestCancellation(NOW);
        operation.requestCancellation(NOW.plusSeconds(1));
        operation.completeCancellation(NOW.plusSeconds(2));
        operation.requestCancellation(NOW.plusSeconds(3));
        operation.completeCancellation(NOW.plusSeconds(4));
        // then
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.CANCELLED);
        assertThat(operation.getCancelRequestedAt()).isEqualTo(NOW);
        assertThat(operation.getFinishedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(operation.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("실행 중 취소는 토큰과 lease를 제거하고 늦은 실패와 마감 처리를 무시한다")
    void requestCancellation_success_invalidatesClaim() {
        // given
        DocumentTopicOperation operation = operation();
        UUID token = operation.claim(NOW);
        // when
        operation.requestCancellation(NOW.plusSeconds(1));
        operation.retry(
                token,
                NOW.plusSeconds(2),
                "TIMEOUT"
        );
        operation.fail(
                "LATE_FAILURE",
                NOW.plusSeconds(3)
        );
        operation.recover(NOW.plusSeconds(1900));
        // then
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.CANCEL_REQUESTED);
        assertThat(operation.getClaimToken()).isNull();
        assertThat(operation.getLeaseUntil()).isNull();
        assertThat(operation.getFinishedAt()).isNull();
        assertThat(
                operation.owns(
                        token,
                        NOW.plusSeconds(2)
                )
        ).isFalse();
        assertThatThrownBy(
                () -> operation.checkpoint(
                        token,
                        0,
                        true,
                        NOW.plusSeconds(2)
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("최종 적용에 진입한 작업은 취소할 수 없다")
    void requestCancellation_failure_applying() {
        // given
        DocumentTopicOperation operation = prepared();
        UUID token = operation.claim(NOW.plusSeconds(1));
        operation.beginApplying(
                token,
                NOW.plusSeconds(1)
        );
        // when & then
        assertThatThrownBy(() -> operation.requestCancellation(NOW.plusSeconds(2)))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("취소");
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.APPLYING);
    }

    @Test
    @DisplayName("실패로 끝난 작업의 취소는 허용하지 않는다")
    void requestCancellation_failure_failed() {
        // given
        DocumentTopicOperation operation = operation();
        operation.fail(
                "FAILED",
                NOW
        );
        // when & then
        assertThatThrownBy(() -> operation.requestCancellation(NOW.plusSeconds(1)))
                .isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("준비가 완료되지 않은 작업은 APPLYING에 진입하지 못한다")
    void beginApplying_failure_incomplete() {
        // given
        DocumentTopicOperation operation = operation();
        operation.capture(1);
        UUID token = operation.claim(NOW);
        // when & then
        assertThatThrownBy(
                () -> operation.beginApplying(
                        token,
                        NOW
                )
        ).isInstanceOf(DocumentException.class);
    }

    @Test
    @DisplayName("적용 단계 재시도는 5초 뒤 한 번만 인계하고 이전 토큰을 차단한다")
    void reclaimApplying_success_preservesBoundary() {
        // given
        DocumentTopicOperation operation = prepared();
        UUID first = operation.claim(NOW.plusSeconds(1));
        operation.beginApplying(
                first,
                NOW.plusSeconds(1)
        );
        // when
        operation.deferApplyingRetry(
                first,
                NOW.plusSeconds(2),
                "STORAGE_ERROR"
        );
        // then
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.APPLYING);
        assertThat(
                operation.ownsApplying(
                        first,
                        NOW.plusSeconds(2)
                )
        ).isFalse();
        assertThat(operation.canReclaimApplying(NOW.plusSeconds(6))).isFalse();
        UUID next = operation.reclaimApplying(NOW.plusSeconds(7));
        assertThat(next).isNotEqualTo(first);
        assertThat(operation.getRetryCount()).isEqualTo(1);
        assertThat(operation.getDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
        operation.deferApplyingRetry(
                next,
                NOW.plusSeconds(8),
                "STORAGE_ERROR"
        );
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.FAILED);
    }

    @Test
    @DisplayName("적용 lease를 잃은 이전 실행자는 성공 처리할 수 없다")
    void succeed_failure_staleApplyingClaim() {
        // given
        DocumentTopicOperation operation = prepared();
        UUID first = operation.claim(NOW.plusSeconds(1));
        operation.beginApplying(
                first,
                NOW.plusSeconds(1)
        );
        UUID next = operation.reclaimApplying(NOW.plusSeconds(182));
        // when & then
        assertThatThrownBy(
                () -> operation.succeed(
                        first,
                        0,
                        NOW.plusSeconds(183)
                )
        ).isInstanceOf(DocumentException.class);
        operation.succeed(
                next,
                0,
                NOW.plusSeconds(183)
        );
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.SUCCEEDED);
        assertThatThrownBy(() -> operation.requestCancellation(NOW.plusSeconds(184)))
                .isInstanceOf(DocumentException.class);
    }

    private DocumentTopicOperation operation() {
        return DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
    }

    private DocumentTopicOperation prepared() {
        DocumentTopicOperation operation = operation();
        UUID token = operation.claim(NOW);
        operation.checkpoint(
                token,
                0,
                true,
                NOW
        );
        return operation;
    }
}
