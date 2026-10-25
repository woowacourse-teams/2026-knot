package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.TranscriptSeamAligner.Token;
import com.knot.backend.recording.application.TranscriptSeamAligner.Window;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptSeamAlignerTest {
    private static final long SECOND = 16_000;

    @Test
    @DisplayName("직전 10초 문맥에서 두 구간이 같이 받아 적은 단어는 한 번만 남긴다")
    void stitch_success_alignedOverlapOnce() {
        // given
        Window left = left(
                token(
                        1,
                        2,
                        "안녕하세요"
                ),
                token(
                        115,
                        115.5,
                        "회의를"
                ),
                token(
                        115.5,
                        116.5,
                        "시작하겠습니다"
                )
        );
        Window right = right(
                token(
                        115.02,
                        115.5,
                        "회의를"
                ),
                token(
                        115.5,
                        116.4,
                        "시작하겠습니다"
                ),
                token(
                        130,
                        131,
                        "첫"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "안녕하세요",
                "회의를",
                "시작하겠습니다",
                "첫"
        );
    }

    @Test
    @DisplayName("겹친 구간에서 실제로 두 번 말한 구절은 하나로 합치지 않는다")
    void stitch_success_genuineRepetitionPreserved() {
        // given
        Window left = left(
                token(
                        112,
                        113,
                        "계속"
                ),
                token(
                        113,
                        114,
                        "깎는다"
                ),
                token(
                        114,
                        115,
                        "계속"
                ),
                token(
                        115,
                        116,
                        "깎는다"
                )
        );
        Window right = right(
                token(
                        112,
                        113,
                        "계속"
                ),
                token(
                        113,
                        114,
                        "깎는다"
                ),
                token(
                        114,
                        115,
                        "계속"
                ),
                token(
                        115,
                        116,
                        "깎는다"
                ),
                token(
                        125,
                        126,
                        "다음"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "계속",
                "깎는다",
                "계속",
                "깎는다",
                "다음"
        );
    }

    @Test
    @DisplayName("띄어쓰기가 달라도 같은 글자의 구절이면 맞춰 이어 붙인다")
    void stitch_success_spanAlignmentAcrossSpacing() {
        // given
        Window left = left(
                token(
                        114,
                        114.5,
                        "서브"
                ),
                token(
                        114.5,
                        115.5,
                        "에이전트를"
                ),
                token(
                        116,
                        117,
                        "만들어요"
                )
        );
        Window right = right(
                token(
                        114,
                        115.5,
                        "서브에이전트를"
                ),
                token(
                        116.5,
                        117.5,
                        "만듭니다"
                ),
                token(
                        125,
                        126,
                        "다음"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "서브",
                "에이전트를",
                "만듭니다",
                "다음"
        );
    }

    @Test
    @DisplayName("겹친 구간에 단어가 없으면 경계 시각으로 자르고 모든 단어를 남긴다")
    void stitch_success_silentOverlap() {
        // given
        Window left = left(
                token(
                        100,
                        101,
                        "앞"
                )
        );
        Window right = right(
                token(
                        125,
                        126,
                        "뒤"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "앞",
                "뒤"
        );
    }

    @Test
    @DisplayName("정렬하지 못하면 경계 시각으로 자르고 오른쪽 첫 단어 시작을 왼쪽 마지막 단어 뒤로 맞춘다")
    void stitch_success_unresolvedTimeCutWithMonotonicStart() {
        // given
        Window left = left(
                token(
                        118,
                        119.9,
                        "왼쪽"
                )
        );
        Window right = right(
                token(
                        117,
                        123,
                        "긴단어"
                ),
                token(
                        125,
                        126,
                        "뒤"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "왼쪽",
                "긴단어",
                "뒤"
        );
        assertThat(
                kept.getLast()
                        .getFirst()
                        .startFrame()
        ).isEqualTo(Math.round(119.9 * SECOND));
    }

    @Test
    @DisplayName("시간으로 자른 오른쪽 구간의 뒤 단어들도 앞 단어보다 먼저 시작하지 않게 맞춘다")
    void stitch_success_unresolvedTimeCutKeepsLaterTokensMonotonic() {
        // given
        Window left = left(
                token(
                        118,
                        119.9,
                        "왼쪽"
                )
        );
        Window right = right(
                token(
                        117,
                        123,
                        "긴단어"
                ),
                token(
                        119.5,
                        121,
                        "다음"
                ),
                token(
                        125,
                        126,
                        "뒤"
                )
        );

        // when
        List<List<Token>> kept = TranscriptSeamAligner.stitch(
                List.of(
                        left,
                        right
                )
        );

        // then
        assertThat(texts(kept)).containsExactly(
                "왼쪽",
                "긴단어",
                "다음",
                "뒤"
        );
        assertThat(
                kept.getLast()
                        .stream()
                        .map(Token::startFrame)
                        .toList()
        ).isSorted();
    }

    private Window left(Token... tokens) {
        return new Window(
                0,
                120 * SECOND,
                0,
                120 * SECOND,
                List.of(tokens)
        );
    }

    private Window right(Token... tokens) {
        return new Window(
                120 * SECOND,
                240 * SECOND,
                110 * SECOND,
                240 * SECOND,
                List.of(tokens)
        );
    }

    private Token token(
            double start,
            double end,
            String text
    ) {
        return new Token(
                Math.round(start * SECOND),
                Math.round(end * SECOND),
                text,
                "a"
        );
    }

    private List<String> texts(List<List<Token>> kept) {
        return kept.stream()
                .flatMap(List::stream)
                .map(Token::text)
                .toList();
    }
}
