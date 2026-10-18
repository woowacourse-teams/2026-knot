package com.knot.backend.recording.infrastructure.storage;

final class PcmSupplyClock {
    private static final long MAX_PCM_BYTES = 230_400_000;
    private static final long BYTES_PER_SECOND = 32_000;

    private PcmSupplyClock() {}

    static long availableNanos(long completeByteCount) {
        if (completeByteCount < 0 || completeByteCount > MAX_PCM_BYTES || completeByteCount % 2 != 0) {
            throw new IllegalArgumentException("PCM16의 유효한 누적 바이트 수가 필요합니다");
        }
        return completeByteCount * 1_000_000_000L / BYTES_PER_SECOND;
    }
}
