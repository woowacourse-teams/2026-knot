package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingTitleGenerationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final Duration BACKOFF = Duration.ofSeconds(30);

    @Test
    @DisplayName("접수하면 제목 없이 바로 실행할 수 있는 대기 상태다")
    void queue_success() {
        // when
        RecordingTitleGeneration generation = RecordingTitleGeneration.queue(
                7L,
                NOW
        );

        // then
        assertThat(generation.getRecordingId()).isEqualTo(7L);
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.QUEUED);
        assertThat(generation.getAttemptCount()).isZero();
        assertThat(generation.getNextRunAt()).isEqualTo(NOW);
        assertThat(generation.getTitle()).isNull();
    }

    @Test
    @DisplayName("양수가 아닌 녹음 ID로는 접수하지 않는다")
    void queue_fail_invalidRecording() {
        // when & then
        assertThatThrownBy(
                () -> RecordingTitleGeneration.queue(
                        0L,
                        NOW
                )
        ).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("실행 시각이 된 대기 작업을 선점하면 시도 횟수를 올리고 임대 시간을 잡는다")
    void claim_success() {
        // given
        RecordingTitleGeneration generation = queued();

        // when
        boolean claimed = generation.claim(
                NOW,
                LEASE
        );

        // then
        assertThat(claimed).isTrue();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.RUNNING);
        assertThat(generation.getAttemptCount()).isEqualTo(1);
        assertThat(generation.getLeaseUntil()).isEqualTo(NOW.plus(LEASE));
    }

    @Test
    @DisplayName("임대 시간이 남은 실행 중 작업은 다시 선점하지 않는다")
    void claim_fail_running() {
        // given
        RecordingTitleGeneration generation = claimed();

        // when
        boolean claimed = generation.claim(
                NOW.plusSeconds(1),
                LEASE
        );

        // then
        assertThat(claimed).isFalse();
        assertThat(generation.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 대기 시각 전에는 선점하지 않는다")
    void claim_fail_beforeNextRun() {
        // given
        RecordingTitleGeneration generation = claimed();
        generation.fail(
                1,
                "LLM_TIMEOUT",
                NOW,
                BACKOFF
        );

        // when
        boolean claimed = generation.claim(
                NOW.plus(BACKOFF)
                        .minusMillis(1),
                LEASE
        );

        // then
        assertThat(claimed).isFalse();
    }

    @Test
    @DisplayName("서버가 멈춰 임대 시간이 지난 실행 중 작업은 다시 선점한다")
    void claim_success_abandoned() {
        // given
        RecordingTitleGeneration generation = claimed();

        // when
        boolean claimed = generation.claim(
                NOW.plus(LEASE),
                LEASE
        );

        // then
        assertThat(claimed).isTrue();
        assertThat(generation.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막 시도가 임대 시간을 넘기면 다시 선점하지 않고 실패로 끝낸다")
    void claim_fail_abandonedLastAttempt() {
        // given
        RecordingTitleGeneration generation = exhaustedRunning();

        // when
        boolean claimed = generation.claim(
                NOW.plus(LEASE.multipliedBy(4)),
                LEASE
        );

        // then
        assertThat(claimed).isFalse();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.FAILED);
        assertThat(generation.getFailureCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(generation.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("현재 시도의 결과를 저장하면 정리한 제목으로 완료한다")
    void succeed_success() {
        // given
        RecordingTitleGeneration generation = claimed();

        // when
        boolean applied = generation.succeed(
                1,
                "  회원 탈퇴 정책 논의 ",
                NOW.plusSeconds(3)
        );

        // then
        assertThat(applied).isTrue();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.SUCCEEDED);
        assertThat(generation.getTitle()).isEqualTo("회원 탈퇴 정책 논의");
        assertThat(generation.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(generation.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("다른 실행자가 다시 선점한 뒤 도착한 이전 시도의 결과는 버린다")
    void succeed_fail_staleAttempt() {
        // given
        RecordingTitleGeneration generation = claimed();
        generation.claim(
                NOW.plus(LEASE),
                LEASE
        );

        // when
        boolean applied = generation.succeed(
                1,
                "이전 결과",
                NOW.plus(LEASE)
        );

        // then
        assertThat(applied).isFalse();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.RUNNING);
        assertThat(generation.getTitle()).isNull();
    }

    @Test
    @DisplayName("시도가 남은 실패는 대기 시간 뒤 다시 실행하도록 되돌린다")
    void fail_success_retryLater() {
        // given
        RecordingTitleGeneration generation = claimed();

        // when
        boolean applied = generation.fail(
                1,
                "LLM_TIMEOUT",
                NOW,
                BACKOFF
        );

        // then
        assertThat(applied).isTrue();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.QUEUED);
        assertThat(generation.getNextRunAt()).isEqualTo(NOW.plus(BACKOFF));
        assertThat(generation.getFailureCode()).isEqualTo("LLM_TIMEOUT");
        assertThat(generation.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("마지막 시도까지 실패하면 제목 없이 실패로 끝내고 더 선점하지 않는다")
    void fail_success_exhausted() {
        // given
        RecordingTitleGeneration generation = exhaustedRunning();

        // when
        boolean applied = generation.fail(
                RecordingTitleGeneration.MAX_ATTEMPTS,
                "LLM_UNAVAILABLE",
                NOW.plus(LEASE),
                BACKOFF
        );

        // then
        assertThat(applied).isTrue();
        assertThat(generation.getStatus()).isEqualTo(RecordingTitleGenerationStatus.FAILED);
        assertThat(generation.getTitle()).isNull();
        assertThat(
                generation.claim(
                        NOW.plus(Duration.ofDays(1)),
                        LEASE
                )
        ).isFalse();
    }

    private RecordingTitleGeneration queued() {
        return RecordingTitleGeneration.queue(
                7L,
                NOW
        );
    }

    private RecordingTitleGeneration claimed() {
        RecordingTitleGeneration generation = queued();
        generation.claim(
                NOW,
                LEASE
        );
        return generation;
    }

    private RecordingTitleGeneration exhaustedRunning() {
        RecordingTitleGeneration generation = queued();
        for (int attempt = 0; attempt < RecordingTitleGeneration.MAX_ATTEMPTS; attempt++) {
            generation.claim(
                    NOW.plus(LEASE.multipliedBy(attempt)),
                    LEASE
            );
        }
        return generation;
    }
}
