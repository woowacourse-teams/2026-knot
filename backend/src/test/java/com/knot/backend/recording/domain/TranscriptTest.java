package com.knot.backend.recording.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptTest {
    @Test
    @DisplayName("전사 텍스트와 원본 녹음을 저장할 원문을 생성한다")
    void create_success() {
        // when
        Transcript transcript = Transcript.create(
                1,
                "원문",
                Instant.parse("2026-10-06T00:00:00Z")
        );

        // then
        assertThat(transcript.getContent()).isEqualTo("원문");
        assertThat(transcript.getRecordingSessionId()).isEqualTo(1);
    }

    @Test
    @DisplayName("누락된 전사 텍스트는 저장 원문으로 생성하지 못한다")
    void create_failure_missingText() {
        // when
        Throwable failure = catchThrowable(
                () -> Transcript.create(
                        1,
                        null,
                        Instant.now()
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("빈 전사 원문은 저장 원문으로 생성하지 못한다")
    void create_failure_blankText() {
        // when
        Throwable failure = catchThrowable(
                () -> Transcript.create(
                        1,
                        " ",
                        Instant.now()
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }
}
