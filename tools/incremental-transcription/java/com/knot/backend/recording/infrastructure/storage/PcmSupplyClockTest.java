package com.knot.backend.recording.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PcmSupplyClockTest {
    @ParameterizedTest
    @CsvSource({"160000,5000000000", "1952000,61000000000", "2240000,70000000000", "230400000,7200000000000"})
    void availableNanos_tracksActualSamplesIncludingPartialFinalChunk(
            long byteCount,
            long expected
    ) {
        // given
        long supplied = byteCount;

        // when
        long nanos = PcmSupplyClock.availableNanos(supplied);

        // then
        assertThat(nanos).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {-2, 1, 230400002})
    void availableNanos_rejectsInvalidPcmRanges(long byteCount) {
        // given
        long supplied = byteCount;

        // when
        Throwable failure = catchThrowable(() -> PcmSupplyClock.availableNanos(supplied));

        // then
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }
}
