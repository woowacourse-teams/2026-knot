package com.knot.backend.recording.application.dto.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.TranscriptUtterance;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiTranscriptTest {

    @Test
    @DisplayName("지원하는 스키마의 발화를 순번대로 밀리초 발화로 바꾼다")
    void toUtterances_success_convertsInOrder() {
        // given
        AiTranscript transcript = new AiTranscript(
                1,
                "{}",
                List.of(
                        utterance(
                                0,
                                1,
                                2
                        ),
                        utterance(
                                1,
                                2,
                                3
                        )
                )
        );

        // when
        List<TranscriptUtterance> utterances = transcript.toUtterances();

        // then
        assertThat(utterances).extracting(TranscriptUtterance::startMillis)
                .containsExactly(
                        1000L,
                        2000L
                );
    }

    @Test
    @DisplayName("지원하지 않는 스키마 버전의 결과는 거절한다")
    void toUtterances_failure_unsupportedSchema() {
        // given
        AiTranscript transcript = new AiTranscript(
                2,
                "{}",
                List.of(
                        utterance(
                                0,
                                1,
                                2
                        )
                )
        );

        // when
        Throwable failure = catchThrowable(transcript::toUtterances);

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    @Test
    @DisplayName("발화 순번이 목록 순서와 다르면 거절한다")
    void toUtterances_failure_ordMismatch() {
        // given
        AiTranscript transcript = new AiTranscript(
                1,
                "{}",
                List.of(
                        utterance(
                                1,
                                1,
                                2
                        )
                )
        );

        // when
        Throwable failure = catchThrowable(transcript::toUtterances);

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    private AiTranscript.Utterance utterance(
            int ord,
            double startSec,
            double endSec
    ) {
        return new AiTranscript.Utterance(
                ord,
                "speaker",
                startSec,
                endSec,
                "발화"
        );
    }
}
