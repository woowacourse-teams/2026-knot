package com.knot.ai.transcription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowInputTest {
    private static final String MANIFEST = "a".repeat(64);
    private static final long START = 16_000;

    @Test
    @DisplayName("새 음성 2분과 직전 10초인 130초 구간을 받는다")
    void create_success_hundredThirtySeconds() {
        // given
        long end = START + 130 * 16_000L;

        // when
        WindowInput window = new WindowInput(
                "pcm-window-v1",
                MANIFEST,
                START,
                end,
                DecodePolicy.WITH_VOCABULARY
        );

        // then
        assertThat(window.inputEndFrame() - window.inputStartFrame()).isEqualTo(2_080_000);
    }

    @Test
    @DisplayName("130초를 한 frame이라도 넘는 구간은 거절한다")
    void create_failure_longerThanHundredThirtySeconds() {
        // given
        long end = START + 130 * 16_000L + 1;

        // when & then
        assertThatThrownBy(
                () -> new WindowInput(
                        "pcm-window-v1",
                        MANIFEST,
                        START,
                        end
                )
        ).isInstanceOf(TranscriptionException.class);
    }

    @Test
    @DisplayName("디코딩 정책을 생략하면 기본 디코딩만 쓰는 기존 동작을 유지한다")
    void create_success_defaultOnlyWithoutPolicy() {
        // given
        long end = START + 16_000;

        // when
        WindowInput window = new WindowInput(
                "pcm-window-v1",
                MANIFEST,
                START,
                end,
                null
        );

        // then
        assertThat(window.decodePolicy()).isEqualTo(DecodePolicy.DEFAULT_ONLY);
        assertThat(window).isEqualTo(
                new WindowInput(
                        "pcm-window-v1",
                        MANIFEST,
                        START,
                        end
                )
        );
    }
}
