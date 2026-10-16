package com.knot.backend.recording.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TranscriptSegmentTest {

    @Test
    @DisplayName("시작 시각 0과 미상 종료 시각·화자로 구간을 생성한다")
    void create_success_unknownEndAndSpeaker() {
        // when
        TranscriptSegment segment = TranscriptSegment.create(
                81,
                0,
                0,
                null,
                null,
                "발언"
        );

        // then
        assertThat(segment.getTranscriptId()).isEqualTo(81);
        assertThat(segment.getPosition()).isZero();
        assertThat(segment.getStartMillis()).isZero();
        assertThat(segment.getEndMillis()).isNull();
        assertThat(segment.getSpeakerNumber()).isNull();
        assertThat(segment.getText()).isEqualTo("발언");
    }

    @Test
    @DisplayName("같은 시작·종료 시각과 익명 화자 번호를 유지한다")
    void create_success_knownEndAndSpeaker() {
        // when
        TranscriptSegment segment = TranscriptSegment.create(
                81,
                1,
                1200,
                1200L,
                2,
                "발언"
        );

        // then
        assertThat(segment.getEndMillis()).isEqualTo(1200);
        assertThat(segment.getSpeakerNumber()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "81,-1,0", "81,0,-1"})
    @DisplayName("잘못된 원문 식별자·순서·시작 시각을 거절한다")
    void create_failure_invalidRequiredValues(
            long transcriptId,
            int position,
            long startMillis
    ) {
        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.create(
                        transcriptId,
                        position,
                        startMillis,
                        null,
                        null,
                        "발언"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("시작보다 이른 종료 시각을 거절한다")
    void create_failure_reversedTime() {
        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.create(
                        81,
                        0,
                        100,
                        99L,
                        null,
                        "발언"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("화자 번호는 1 이상이어야 한다")
    void create_failure_invalidSpeaker(int speakerNumber) {
        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.create(
                        81,
                        0,
                        0,
                        null,
                        speakerNumber,
                        "발언"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    @DisplayName("문장이 없거나 공백만 있으면 거절한다")
    void create_failure_blankText(String text) {
        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.create(
                        81,
                        0,
                        0,
                        null,
                        null,
                        text
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("2시간을 넘는 종료 시각의 구간은 만들지 않는다")
    void create_failure_beyondMaxRecording() {
        // given
        long endMillis = RecordingSession.MAX_RECORDING_MILLIS + 1;

        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.create(
                        81,
                        0,
                        0,
                        endMillis,
                        null,
                        "발언"
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("발화 순서대로 위치를 붙이고 화자 라벨마다 처음 나온 순서의 번호를 붙인다")
    void createAll_success_positionsAndSpeakerNumbers() {
        // given
        List<TranscriptUtterance> utterances = List.of(
                new TranscriptUtterance(
                        "B",
                        1,
                        2,
                        "첫 발화"
                ),
                new TranscriptUtterance(
                        "A",
                        2,
                        3,
                        "둘째 발화"
                ),
                new TranscriptUtterance(
                        "B",
                        3,
                        4,
                        "셋째 발화"
                ),
                new TranscriptUtterance(
                        " ",
                        4,
                        5,
                        "화자 미상"
                )
        );

        // when
        List<TranscriptSegment> segments = TranscriptSegment.createAll(
                81,
                utterances
        );

        // then
        assertThat(segments).extracting(TranscriptSegment::getPosition)
                .containsExactly(
                        0,
                        1,
                        2,
                        3
                );
        assertThat(segments).extracting(TranscriptSegment::getSpeakerNumber)
                .containsExactly(
                        1,
                        2,
                        1,
                        null
                );
        assertThat(segments).extracting(TranscriptSegment::getStartMillis)
                .containsExactly(
                        1000L,
                        2000L,
                        3000L,
                        4000L
                );
    }

    @Test
    @DisplayName("발화가 하나도 없으면 구간을 만들지 않는다")
    void createAll_failure_emptyUtterances() {
        // given
        List<TranscriptUtterance> utterances = List.of();

        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.createAll(
                        81,
                        utterances
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }

    @Test
    @DisplayName("앞 발화보다 이른 시작 시각이 나오면 구간을 만들지 않는다")
    void createAll_failure_startGoesBack() {
        // given
        List<TranscriptUtterance> utterances = List.of(
                new TranscriptUtterance(
                        "A",
                        1,
                        2,
                        "첫 발화"
                ),
                new TranscriptUtterance(
                        "A",
                        0.5,
                        1.5,
                        "역전된 발화"
                )
        );

        // when
        Throwable failure = catchThrowable(
                () -> TranscriptSegment.createAll(
                        81,
                        utterances
                )
        );

        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
    }
}
