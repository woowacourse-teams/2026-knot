package com.knot.backend.document.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class DocumentTopicOperationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Test
    @DisplayName("최초 실행 시각은 재시도와 lease 회복 후에도 보존한다")
    void claim_success_firstStartedAtIsPreserved() {
        DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
        assertThat(operation.getStartedAt()).isNull();
        UUID token = operation.claim(NOW.plusSeconds(1));
        operation.retry(
                token,
                NOW.plusSeconds(2),
                "TIMEOUT"
        );
        operation.claim(NOW.plusSeconds(7));
        operation.recover(NOW.plusSeconds(188));
        assertThat(operation.getStartedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("재시도 대기는 공개 RUNNING이며 최초 대기는 QUEUED다")
    void reportedStatus_success_retryIsRunning() {
        assertThat(DocumentTopicOperationStatus.QUEUED.reportedStatus(0))
                .isEqualTo(DocumentTopicOperationStatus.QUEUED);
        assertThat(DocumentTopicOperationStatus.QUEUED.reportedStatus(1))
                .isEqualTo(DocumentTopicOperationStatus.RUNNING);
        assertThat(DocumentTopicOperationStatus.FAILED.reportedStatus(1))
                .isEqualTo(DocumentTopicOperationStatus.FAILED);
    }

    @Test
    @DisplayName("재시도와 재인계가 전체 마감 시각을 연장하지 않는다")
    void claim_success_deadlineIsFixed() {
        DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
        UUID first = operation.claim(NOW);
        operation.retry(
                first,
                NOW.plusSeconds(1),
                "TIMEOUT"
        );
        assertThat(operation.isReady(NOW.plusSeconds(5))).isFalse();
        UUID second = operation.claim(NOW.plusSeconds(6));
        assertThat(second).isNotEqualTo(first);
        assertThat(operation.getDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(
                operation.owns(
                        first,
                        NOW.plusSeconds(7)
                )
        ).isFalse();
    }

    @Test
    @DisplayName("한 처리 단위에서 자동 재시도는 한 번만 허용한다")
    void retry_failure_secondFailure() {
        DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
        UUID token = operation.claim(NOW);
        operation.retry(
                token,
                NOW.plusSeconds(1),
                "TIMEOUT"
        );
        UUID second = operation.claim(NOW.plusSeconds(6));
        operation.retry(
                second,
                NOW.plusSeconds(7),
                "TIMEOUT"
        );
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.FAILED);
    }

    @Test
    @DisplayName("마감 시각에 도달한 작업은 인계하지 않고 실패한다")
    void claim_failure_deadline() {
        DocumentTopicOperation operation = DocumentTopicOperation.reorganize(
                1,
                2,
                UUID.randomUUID(),
                NOW
        );
        operation.expire(NOW.plusSeconds(1800));
        assertThat(operation.getStatus()).isEqualTo(DocumentTopicOperationStatus.FAILED);
        assertThat(operation.isReady(NOW.plusSeconds(1800))).isFalse();
    }
}
