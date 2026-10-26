package com.knot.backend.recording.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 녹음 전체로 다시 묶은 화자 구간에 단어를 배정한다. AI runner의 assignSpeakers(서버 정책: 짧은 화자를 흡수하지
 * 않음)를 같은 결과가 나오도록 옮겼다. 겹침이 가장 긴 화자, 1초 안의 가장 가까운 화자, 직전 화자, UNKNOWN 순으로 정한 뒤
 * 문장 단위 다수결로 덮어쓴다.
 */
final class SpeakerAssigner {
    static final String UNKNOWN_SPEAKER = "UNKNOWN";

    private static final double NEAREST_SPEAKER_TOLERANCE_SEC = 1;
    private static final double SENTENCE_GAP_SEC = 1;
    /** 문장부호가 빠진 구간에서 한 문장이 다른 화자의 말을 삼키지 않게 이 길이부터 segment 경계에서 끊는다 */
    private static final double MAX_SENTENCE_SEC = 8;
    /** 길이 0에 가까운 단어도 표를 잃지 않게 센다 */
    private static final double MIN_VOTE_WEIGHT_SEC = .05;
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!]$");

    private SpeakerAssigner() {}

    record Word(
            double start,
            double end,
            String text
    ) {
    }

    record SpeakerSpan(
            double start,
            double end,
            String speaker
    ) {
    }

    /** segments의 단어를 순서대로 펼친 목록과 같은 길이로 화자를 돌려준다. */
    static List<String> assign(
            List<List<Word>> segments,
            List<SpeakerSpan> spans
    ) {
        List<SpeakerSpan> sorted = spans.stream()
                .sorted(Comparator.comparingDouble(SpeakerSpan::start))
                .toList();
        double[] prefixMaxEnd = new double[sorted.size()];
        for (int index = 0; index < sorted.size(); index++) {
            prefixMaxEnd[index] = sorted.get(index)
                    .end();
            if (index > 0) {
                prefixMaxEnd[index] = Math.max(
                        prefixMaxEnd[index - 1],
                        prefixMaxEnd[index]
                );
            }
        }
        List<Piece> pieces = new ArrayList<>();
        String previous = null;
        for (List<Word> segment : segments) {
            for (int index = 0; index < segment.size(); index++) {
                Word word = segment.get(index);
                String found = overlapping(
                        sorted,
                        prefixMaxEnd,
                        word
                );
                if (found == null) {
                    found = nearest(
                            sorted,
                            word
                    );
                }
                if (found != null) {
                    previous = found;
                }
                pieces.add(
                        new Piece(
                                word,
                                speakerOrUnknown(
                                        found,
                                        previous
                                ),
                                index == segment.size() - 1
                        )
                );
            }
        }
        return voteBySentence(pieces);
    }

    private static String speakerOrUnknown(
            String found,
            String previous
    ) {
        if (found != null) {
            return found;
        }
        if (previous != null) {
            return previous;
        }
        return UNKNOWN_SPEAKER;
    }

    private static String overlapping(
            List<SpeakerSpan> sorted,
            double[] prefixMaxEnd,
            Word word
    ) {
        String best = null;
        double bestOverlap = 0;
        for (int index = upperBound(
                sorted,
                word.end()
        ) - 1; index >= 0 && prefixMaxEnd[index] > word.start(); index--) {
            SpeakerSpan span = sorted.get(index);
            double overlap = Math.min(
                    span.end(),
                    word.end()
            ) - Math.max(span.start(),
                    word.start()
            );
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = span.speaker();
            }
        }
        return best;
    }

    /** start가 end 이상인 첫 위치. 그 앞의 구간만 겹칠 수 있다. */
    private static int upperBound(
            List<SpeakerSpan> sorted,
            double end
    ) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted.get(middle)
                    .start() < end) {
                low = middle + 1;
                continue;
            }
            high = middle;
        }
        return low;
    }

    /** 겹치지 않지만 허용 거리 안의 가장 가까운 화자. 같은 거리면 뒤의 구간을 고른다. */
    private static String nearest(
            List<SpeakerSpan> sorted,
            Word word
    ) {
        String best = null;
        double bestGap = NEAREST_SPEAKER_TOLERANCE_SEC;
        for (SpeakerSpan span : sorted) {
            double gap = Math.max(
                    Math.max(
                            span.start() - word.end(),
                            word.start() - span.end()
                    ),
                    0
            );
            if (gap <= bestGap) {
                bestGap = gap;
                best = span.speaker();
            }
        }
        return best;
    }

    private static List<String> voteBySentence(List<Piece> pieces) {
        List<String> speakers = new ArrayList<>();
        List<Piece> sentence = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            sentence.add(pieces.get(index));
            if (!sentenceEnds(
                    pieces,
                    index,
                    sentence.getFirst()
                            .word()
                            .start()
            )) {
                continue;
            }
            String speaker = majority(sentence);
            sentence.forEach(piece -> speakers.add(speaker));
            sentence.clear();
        }
        return speakers;
    }

    private static boolean sentenceEnds(
            List<Piece> pieces,
            int index,
            double sentenceStart
    ) {
        Piece piece = pieces.get(index);
        if (index + 1 == pieces.size() || SENTENCE_END.matcher(piece.text())
                .find()) {
            return true;
        }
        if (pieces.get(index + 1)
                .word()
                .start()
                - piece.word()
                        .end() >= SENTENCE_GAP_SEC) {
            return true;
        }
        return piece.segmentEnd() && piece.word()
                .end() - sentenceStart >= MAX_SENTENCE_SEC;
    }

    /** 문장 안에서 발화 시간 합이 가장 긴 화자. 같으면 먼저 나온 화자다. */
    private static String majority(List<Piece> sentence) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Piece piece : sentence) {
            totals.merge(
                    piece.speaker(),
                    Math.max(
                            piece.word()
                                    .end()
                                    - piece.word()
                                            .start(),
                            MIN_VOTE_WEIGHT_SEC
                    ),
                    Double::sum
            );
        }
        String best = null;
        double bestTotal = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> total : totals.entrySet()) {
            if (total.getValue() > bestTotal) {
                bestTotal = total.getValue();
                best = total.getKey();
            }
        }
        return best;
    }

    private record Piece(
            Word word,
            String speaker,
            boolean segmentEnd
    ) {
        private String text() {
            return word.text()
                    .strip();
        }
    }
}
