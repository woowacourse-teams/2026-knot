package com.knot.backend.recording.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TranscriptUtteranceTest {

    @Test
    @DisplayName("초 단위 발화 시각을 밀리초로 반올림해 돌려준다")
    void startMillis_success_roundsToMillis() {
        // given
        TranscriptUtterance utterance = new TranscriptUtterance(
                "speaker",
                1.2344,
                2.5006,
                "발화"
        );

        // when
        long startMillis = utterance.startMillis();

        // then
        assertThat(startMillis).isEqualTo(1234);
        assertThat(utterance.endMillis()).isEqualTo(2501);
    }

    @Test
    @DisplayName("녹음 시작과 2시간 상한에 걸친 발화는 받는다")
    void create_success_boundaryTimes() {
        // when
        TranscriptUtterance utterance = new TranscriptUtterance(
                "speaker",
                0,
                7200,
                "발화"
        );

        // then
        assertThat(utterance.endMillis()).isEqualTo(RecordingSession.MAX_RECORDING_MILLIS);
    }

    @ParameterizedTest
    @CsvSource({"NaN,2", "1,NaN", "Infinity,2", "1,Infinity", "-1,2", "1,7201", "1,0.9999"})
    @DisplayName("유한하지 않거나 음수·2시간 초과·역전된 시각의 발화는 받지 않는다")
    void create_failure_invalidTimeRange(
            double startSeconds,
            double endSeconds
    ) {
        // when
        Throwable failure = catchThrowable(
                () -> new TranscriptUtterance(
                        "speaker",
                        startSeconds,
                        endSeconds,
                        "발화"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
    }
}
