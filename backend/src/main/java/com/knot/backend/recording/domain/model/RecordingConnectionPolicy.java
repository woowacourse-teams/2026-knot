package com.knot.backend.recording.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * 녹음 탭의 연결 끊김 판정 기준.
 *
 * @param grace
 *            마지막 생존 시각 뒤 녹음을 끝내지 않고 기다리는 끊김 유예
 * @param disconnectedAfter
 *            마지막 생존 시각 뒤 연결 끊김 상태로 보는 시간
 */
public record RecordingConnectionPolicy(
        Duration grace,
        Duration disconnectedAfter
) {

    public RecordingConnectionPolicy {
        if (!isPositive(grace) || !isPositive(disconnectedAfter)) {
            throw new IllegalArgumentException("녹음 연결 판정 시간은 0보다 커야 합니다");
        }
        if (disconnectedAfter.compareTo(grace) >= 0) {
            throw new IllegalArgumentException("녹음 연결 끊김 표시 시간은 끊김 유예보다 짧아야 합니다");
        }
    }

    public Instant expiresAt(Instant lastSeenAt) {
        return lastSeenAt.plus(grace);
    }

    public Instant expiredLastSeenThreshold(Instant now) {
        return now.minus(grace);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && duration.isPositive();
    }
}
