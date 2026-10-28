package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.recording.domain.RecordingException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingMeetingSummaryTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(180);
    private static final String CONTENT = "## 한눈에 보기\n\n### 정한 방향\n- 정책을 유지한다.\n\n회의 핵심 결론: 정책을 유지한다.";

    @Test
    void renew_extendsOnlyCurrentUnexpiredClaim() {
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        assertThat(
                summary.renew(
                        token,
                        NOW.plusSeconds(120),
                        LEASE
                )
        ).isTrue();
        assertThat(
                summary.renew(
                        UUID.randomUUID(),
                        NOW.plusSeconds(121),
                        LEASE
                )
        ).isFalse();
        assertThat(
                summary.succeed(
                        token,
                        CONTENT,
                        NOW.plusSeconds(181)
                )
        ).isTrue();
    }

    @Test
    void renew_rejectsExpiredClaim() {
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        assertThat(
                summary.renew(
                        token,
                        NOW.plusSeconds(180),
                        LEASE
                )
        ).isFalse();
    }

    @Test
    @DisplayName("선점한 요약의 정상 결과를 한 번 저장하고 성공 결과를 유지한다")
    void succeed_success() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        // when
        boolean stored = summary.succeed(
                token,
                CONTENT,
                NOW.plusSeconds(1)
        );
        // then
        assertThat(stored).isTrue();
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.SUCCEEDED);
        assertThat(summary.getContent()).isEqualTo(CONTENT);
        assertThat(
                summary.claim(
                        NOW.plusSeconds(200),
                        LEASE
                )
        ).isEmpty();
        assertThat(summary.canRetry(true)).isFalse();
    }

    @Test
    @DisplayName("사용자 재시도는 횟수와 기간 제한 없이 자동 재시도 예산만 초기화한다")
    void retry_success_withoutLimit() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        // when
        for (int index = 0; index < 5; index++) {
            Instant current = NOW.plusSeconds(index * 900000L);
            UUID token = summary.claim(
                    current,
                    LEASE
            )
                    .orElseThrow();
            summary.fail(
                    token,
                    "INVALID_RESPONSE",
                    false,
                    current.plusSeconds(1)
            );
            summary.retry(
                    true,
                    current.plusSeconds(2)
            );
        }
        // then
        assertThat(summary.getUserRetryCount()).isEqualTo(5);
        assertThat(summary.getAttemptNo()).isEqualTo(5);
        assertThat(summary.getAutomaticRetryCount()).isZero();
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.QUEUED);
    }

    @Test
    @DisplayName("일시 오류는 5초 뒤 한 번만 자동 재시도하고 그 뒤 실패한다")
    void fail_success_oneAutomaticRetry() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID first = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        summary.fail(
                first,
                "LLM_TIMEOUT",
                true,
                NOW.plusSeconds(1)
        );
        // when
        assertThat(
                summary.claim(
                        NOW.plusSeconds(5),
                        LEASE
                )
        ).isEmpty();
        UUID second = summary.claim(
                NOW.plusSeconds(6),
                LEASE
        )
                .orElseThrow();
        summary.fail(
                second,
                "LLM_TIMEOUT",
                true,
                NOW.plusSeconds(7)
        );
        // then
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.FAILED);
        assertThat(summary.getAutomaticRetryCount()).isEqualTo(1);
        assertThat(summary.getUserRetryCount()).isZero();
    }

    @Test
    @DisplayName("서버 중단 뒤 실행권을 인계하고 이전 응답을 저장하지 않는다")
    void claim_success_recoversExpiredLease() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID old = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        // when
        UUID current = summary.claim(
                NOW.plusSeconds(180),
                LEASE
        )
                .orElseThrow();
        // then
        assertThat(
                summary.succeed(
                        old,
                        CONTENT,
                        NOW.plusSeconds(181)
                )
        ).isFalse();
        assertThat(
                summary.succeed(
                        current,
                        CONTENT,
                        NOW.plusSeconds(181)
                )
        ).isTrue();
    }

    @Test
    @DisplayName("lease 만료가 반복돼도 자동 복구 횟수를 초기화하지 않는다")
    void claim_failure_repeatedLeaseExpiry() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        summary.claim(
                NOW,
                LEASE
        );
        summary.claim(
                NOW.plusSeconds(180),
                LEASE
        );
        // when
        assertThat(
                summary.claim(
                        NOW.plusSeconds(360),
                        LEASE
                )
        ).isEmpty();
        // then
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.FAILED);
    }

    @Test
    @DisplayName("현재 실행권이라도 만료된 응답은 성공 저장하지 않는다")
    void succeed_failure_expiredLease() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        // when & then
        assertThat(
                summary.succeed(
                        token,
                        CONTENT,
                        NOW.plusSeconds(180)
                )
        ).isFalse();
    }

    @Test
    @DisplayName("이미 접수된 요약과 성공 요약은 사용자 재접수를 거절한다")
    void retry_failure_notFailed() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        // when & then
        assertThatThrownBy(
                () -> summary.retry(
                        true,
                        NOW
                )
        ).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("실패해도 입력 전사를 사용할 수 없으면 다시 접수하지 않는다")
    void retry_failure_inputUnavailable() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        summary.fail(
                token,
                "INPUT_UNAVAILABLE",
                false,
                NOW.plusSeconds(1)
        );
        // when & then
        assertThat(summary.canRetry(false)).isFalse();
        assertThatThrownBy(
                () -> summary.retry(
                        false,
                        NOW.plusSeconds(2)
                )
        ).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("마지막 회의 핵심 결론이 누락된 응답은 완료 요약으로 저장하지 않는다")
    void succeed_failure_missingConclusion() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        // when & then
        assertThatThrownBy(
                () -> summary.succeed(
                        token,
                        "## 한눈에 보기\n\n정리: 논의만 남았다.",
                        NOW.plusSeconds(1)
                )
        ).isInstanceOf(RecordingException.class);
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.RUNNING);
    }

    @Test
    @DisplayName("잘못된 요약 본문은 성공 상태로 저장하지 않는다")
    void succeed_failure_invalidContent() {
        // given
        RecordingMeetingSummary summary = RecordingMeetingSummary.queue(
                1,
                2,
                NOW
        );
        UUID token = summary.claim(
                NOW,
                LEASE
        )
                .orElseThrow();
        // when & then
        assertThatThrownBy(
                () -> summary.succeed(
                        token,
                        "\u00a0",
                        NOW.plusSeconds(1)
                )
        ).isInstanceOf(RecordingException.class);
        assertThat(summary.getStatus()).isEqualTo(MeetingSummaryStatus.RUNNING);
    }
}
