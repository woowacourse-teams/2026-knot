package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.SpeakerAssigner.SpeakerSpan;
import com.knot.backend.recording.application.SpeakerAssigner.Word;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpeakerAssignerTest {
    @Test
    @DisplayName("겹치는 화자가 없으면 1초 안의 가장 가까운 화자, 그다음 직전 화자를 쓴다")
    void assign_success_overlapNearestPrevious() {
        // given
        List<List<Word>> segments = List.of(
                List.of(
                        new Word(
                                0,
                                1,
                                "먼저."
                        )
                ),
                List.of(
                        new Word(
                                1.5,
                                2,
                                "가까움."
                        )
                ),
                List.of(
                        new Word(
                                10,
                                11,
                                "멀리."
                        )
                )
        );
        List<SpeakerSpan> spans = List.of(
                new SpeakerSpan(
                        0,
                        1,
                        "SPEAKER_0"
                )
        );

        // when
        List<String> speakers = SpeakerAssigner.assign(
                segments,
                spans
        );

        // then
        assertThat(speakers).containsExactly(
                "SPEAKER_0",
                "SPEAKER_0",
                "SPEAKER_0"
        );
    }

    @Test
    @DisplayName("화자 근거가 없으면 UNKNOWN으로 보존한다")
    void assign_success_unknownWithoutEvidence() {
        // when
        List<String> speakers = SpeakerAssigner.assign(
                List.of(
                        List.of(
                                new Word(
                                        0,
                                        1,
                                        "혼자."
                                )
                        )
                ),
                List.of()
        );

        // then
        assertThat(speakers).containsExactly(SpeakerAssigner.UNKNOWN_SPEAKER);
    }

    @Test
    @DisplayName("한 문장 안의 단어는 발화 시간이 가장 긴 화자로 맞춘다")
    void assign_success_sentenceMajority() {
        // given
        List<List<Word>> segments = List.of(
                List.of(
                        new Word(
                                0,
                                1.5,
                                "오늘"
                        ),
                        new Word(
                                1.5,
                                1.8,
                                "회의."
                        ),
                        new Word(
                                3,
                                4,
                                "네."
                        )
                )
        );
        List<SpeakerSpan> spans = List.of(
                new SpeakerSpan(
                        0,
                        1.5,
                        "SPEAKER_0"
                ),
                new SpeakerSpan(
                        1.5,
                        4,
                        "SPEAKER_1"
                )
        );

        // when
        List<String> speakers = SpeakerAssigner.assign(
                segments,
                spans
        );

        // then
        assertThat(speakers).containsExactly(
                "SPEAKER_0",
                "SPEAKER_0",
                "SPEAKER_1"
        );
    }
}
