package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingConnectionPolicyTest {
    private static final Instant LAST_SEEN_AT = Instant.parse("2026-10-28T00:00:00Z");
    private static final RecordingConnectionPolicy POLICY = new RecordingConnectionPolicy(
            Duration.ofMinutes(30),
            Duration.ofSeconds(30)
    );

    @Test
    @DisplayName("만료 예정 시각은 마지막 생존 시각에 끊김 유예를 더한 시각이다")
    void expiresAt_success() {
        // given
        Instant expectedExpiresAt = Instant.parse("2026-10-28T00:30:00Z");

        // when
        Instant expiresAt = POLICY.expiresAt(LAST_SEEN_AT);

        // then
        assertThat(expiresAt).isEqualTo(expectedExpiresAt);
    }

    @Test
    @DisplayName("회수 기준 시각은 현재 시각에서 끊김 유예를 뺀 시각이다")
    void expiredLastSeenThreshold_success() {
        // given
        Instant now = Instant.parse("2026-10-28T00:30:00Z");

        // when
        Instant threshold = POLICY.expiredLastSeenThreshold(now);

        // then
        assertThat(threshold).isEqualTo(LAST_SEEN_AT);
    }

    @Test
    @DisplayName("끊김 표시 시간이 끊김 유예보다 짧지 않으면 설정을 거부한다")
    void create_failure_disconnectedAfterNotShorterThanGrace() {
        // given
        Duration grace = Duration.ofSeconds(30);

        // when
        Throwable thrown = catchThrowable(
                () -> new RecordingConnectionPolicy(
                        grace,
                        grace
                )
        );

        // then
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("0 이하나 없는 시간은 설정을 거부한다")
    void create_failure_nonPositive() {
        // given
        Duration zero = Duration.ZERO;

        // when
        Throwable thrown = catchThrowable(
                () -> new RecordingConnectionPolicy(
                        Duration.ofMinutes(30),
                        zero
                )
        );

        // then
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }
}
