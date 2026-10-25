package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 2분 구간의 직전 10초 문맥에서 같은 말을 두 구간이 모두 받아 적으므로, 겹친 단어를 정렬해 한쪽만 남긴다. 2026-10 구간 전사
 * 실험의 이음매 규칙(stitch_overlap.py, stitch_v2.py)을 그대로 옮겼다. 정렬하지 못한 이음매는 경계 시각으로
 * 자르고, 시작 시각이 역전되지 않게 오른쪽 첫 단어의 시작만 맞춘다.
 */
final class TranscriptSeamAligner {
    private static final double FRAMES_PER_SECOND = 16_000;
    /** 같은 단어로 볼 두 관측의 중앙 시각 차이 */
    private static final double MATCH_TOLERANCE_SEC = 1.5;
    /** 단어 길이가 관측마다 달라도 같은 단어로 볼 구간 사이 간격 */
    private static final double INTERVAL_GAP_SEC = .15;
    private static final int MIN_RUN_CHARACTERS = 4;
    private static final int MAX_SPAN_WORDS = 4;
    private static final int MIN_SPAN_CHARACTERS = 6;
    /** 시각 역전을 막으려고 당긴 첫 단어가 가지는 최소 길이(10ms) */
    private static final long CLAMPED_MIN_FRAMES = 160;

    private TranscriptSeamAligner() {}

    record Token(
            long startFrame,
            long endFrame,
            String text,
            String speakerId
    ) {
        private double start() {
            return startFrame / FRAMES_PER_SECOND;
        }

        private double end() {
            return endFrame / FRAMES_PER_SECOND;
        }

        private double midpoint() {
            return (start() + end()) / 2;
        }
    }

    record Window(
            long coreStartFrame,
            long coreEndFrame,
            long inputStartFrame,
            long inputEndFrame,
            List<Token> tokens
    ) {
    }

    /** 구간마다 최종 전사에 남길 단어. 바깥 목록의 순서는 입력 구간 순서와 같다. */
    static List<List<Token>> stitch(List<Window> windows) {
        windows.forEach(window -> validate(window.tokens()));
        int[] starts = new int[windows.size()];
        int[] ends = windows.stream()
                .mapToInt(
                        window -> window.tokens()
                                .size()
                )
                .toArray();
        for (int index = 0; index + 1 < windows.size(); index++) {
            Window left = windows.get(index);
            Window right = windows.get(index + 1);
            if (left.coreEndFrame() != right.coreStartFrame()) {
                throw invalidData();
            }
            int[] cut = chooseSeam(
                    left.tokens(),
                    right.tokens(),
                    left.coreEndFrame() / FRAMES_PER_SECOND,
                    Math.max(
                            left.inputStartFrame(),
                            right.inputStartFrame()
                    ) / FRAMES_PER_SECOND,
                    Math.min(
                            left.inputEndFrame(),
                            right.inputEndFrame()
                    ) / FRAMES_PER_SECOND
            );
            ends[index] = cut[0];
            starts[index + 1] = cut[1];
        }
        List<List<Token>> kept = new ArrayList<>();
        Token previous = null;
        for (int index = 0; index < windows.size(); index++) {
            if (starts[index] > ends[index]) {
                throw invalidData();
            }
            List<Token> part = new ArrayList<>(
                    windows.get(index)
                            .tokens()
                            .subList(
                                    starts[index],
                                    ends[index]
                            )
            );
            if (previous != null && !part.isEmpty() && part.getFirst()
                    .startFrame() < previous.startFrame()) {
                Token first = part.getFirst();
                part.set(
                        0,
                        new Token(
                                Math.min(
                                        previous.endFrame(),
                                        first.endFrame() - CLAMPED_MIN_FRAMES
                                ),
                                first.endFrame(),
                                first.text(),
                                first.speakerId()
                        )
                );
            }
            if (!part.isEmpty()) {
                previous = part.getLast();
            }
            kept.add(List.copyOf(part));
        }
        validate(
                kept.stream()
                        .flatMap(List::stream)
                        .toList()
        );
        return List.copyOf(kept);
    }

    /** 왼쪽에서 남길 끝(제외)과 오른쪽에서 이어 갈 시작을 돌려준다. */
    private static int[] chooseSeam(
            List<Token> left,
            List<Token> right,
            double boundary,
            double overlapStart,
            double overlapEnd
    ) {
        List<Integer> leftIndexes = overlapping(
                left,
                overlapStart,
                overlapEnd
        );
        List<Integer> rightIndexes = overlapping(
                right,
                overlapStart,
                overlapEnd
        );
        List<Token> a = leftIndexes.stream()
                .map(left::get)
                .toList();
        List<Token> b = rightIndexes.stream()
                .map(right::get)
                .toList();
        int[] aligned = alignedRunCut(
                a,
                b,
                leftIndexes,
                rightIndexes,
                boundary
        );
        if (aligned != null) {
            return aligned;
        }
        int[] timeCut = {cutByMidpoint(
                left,
                boundary
        ), cutByMidpoint(right,
                boundary
        )};
        if (a.isEmpty() && b.isEmpty()) {
            return timeCut;
        }
        int[] span = spanCut(
                left,
                right,
                leftIndexes,
                rightIndexes,
                boundary
        );
        if (span != null) {
            return span;
        }
        return timeCut;
    }

    private static List<Integer> overlapping(
            List<Token> tokens,
            double start,
            double end
    ) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.end() > start && token.start() < end) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static int[] alignedRunCut(
            List<Token> a,
            List<Token> b,
            List<Integer> leftIndexes,
            List<Integer> rightIndexes,
            double boundary
    ) {
        List<List<int[]>> runs = new ArrayList<>();
        for (int[] pair : alignedPairs(
                a,
                b
        )) {
            if (!runs.isEmpty() && continues(
                    runs.getLast(),
                    pair
            )) {
                runs.getLast()
                        .add(pair);
                continue;
            }
            List<int[]> run = new ArrayList<>();
            run.add(pair);
            runs.add(run);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (List<int[]> run : runs) {
            int characters = run.stream()
                    .mapToInt(
                            pair -> length(
                                    normalized(
                                            a.get(pair[0])
                                                    .text()
                                    )
                            )
                    )
                    .sum();
            if (run.size() < 2 || characters < MIN_RUN_CHARACTERS) {
                continue;
            }
            List<String> phrase = run.stream()
                    .map(
                            pair -> normalized(
                                    a.get(pair[0])
                                            .text()
                            )
                    )
                    .toList();
            if (occurrences(
                    a,
                    phrase
            ) != occurrences(
                    b,
                    phrase
            )) {
                continue;
            }
            for (int[] pair : run) {
                Token leftToken = a.get(pair[0]);
                Token rightToken = b.get(pair[1]);
                if (pair[1] + 1 < b.size() && b.get(pair[1] + 1)
                        .start() < leftToken.start()) {
                    continue;
                }
                double meanEnd = (leftToken.end() + rightToken.end()) / 2;
                double cost = Math.abs(meanEnd - boundary)
                        + .25 * Math.abs(leftToken.midpoint() - rightToken.midpoint());
                candidates.add(
                        new Candidate(
                                cost,
                                -run.size(),
                                leftIndexes.get(pair[0]),
                                rightIndexes.get(pair[1]),
                                ""
                        )
                );
            }
        }
        return best(candidates);
    }

    private static boolean continues(
            List<int[]> run,
            int[] pair
    ) {
        int[] last = run.getLast();
        return pair[0] == last[0] + 1 && pair[1] == last[1] + 1;
    }

    /** 시각이 가깝고 정규화한 글자가 같은 단어끼리 순서를 지켜 짝짓는다. 같은 단어 반복을 하나로 합치지 않는다. */
    private static List<int[]> alignedPairs(
            List<Token> left,
            List<Token> right
    ) {
        int rows = left.size();
        int columns = right.size();
        double[][] scores = new double[rows + 1][columns + 1];
        char[][] paths = new char[rows + 1][columns + 1];
        for (int i = 1; i <= rows; i++) {
            for (int j = 1; j <= columns; j++) {
                Token a = left.get(i - 1);
                Token b = right.get(j - 1);
                double score = scores[i - 1][j];
                char path = 'L';
                if (scores[i][j - 1] > score) {
                    score = scores[i][j - 1];
                    path = 'R';
                }
                String key = normalized(a.text());
                double distance = Math.abs(a.midpoint() - b.midpoint());
                double gap = Math.max(
                        0,
                        Math.max(
                                a.start(),
                                b.start()
                        ) - Math.min(a.end(),
                                b.end()
                        )
                );
                boolean compatible = distance <= MATCH_TOLERANCE_SEC || gap <= INTERVAL_GAP_SEC;
                if (!key.isEmpty() && key.equals(normalized(b.text())) && compatible) {
                    double reward = 4 + Math.min(
                            length(key),
                            6
                    ) / 6.0 - Math.min(
                            distance,
                            2
                    );
                    if (scores[i - 1][j - 1] + reward > score) {
                        score = scores[i - 1][j - 1] + reward;
                        path = 'M';
                    }
                }
                scores[i][j] = score;
                paths[i][j] = path;
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int i = rows;
        int j = columns;
        while (i > 0 && j > 0) {
            if (paths[i][j] == 'M') {
                pairs.addFirst(new int[]{i - 1, j - 1});
                i--;
                j--;
                continue;
            }
            if (paths[i][j] == 'L') {
                i--;
                continue;
            }
            j--;
        }
        return pairs;
    }

    /** 단어 경계가 관측마다 달라도(예: "서브 에이전트"와 "서브에이전트") 같은 글자의 4단어 이내 구절을 맞춘다. */
    private static int[] spanCut(
            List<Token> left,
            List<Token> right,
            List<Integer> leftIndexes,
            List<Integer> rightIndexes,
            double boundary
    ) {
        String leftText = joined(
                left,
                leftIndexes
        );
        String rightText = joined(
                right,
                rightIndexes
        );
        List<Candidate> candidates = new ArrayList<>();
        for (Span leftSpan : spans(
                left,
                leftIndexes
        )) {
            for (Span rightSpan : spans(
                    right,
                    rightIndexes
            )) {
                if (!leftSpan.key()
                        .equals(rightSpan.key()) || leftSpan.size() + rightSpan.size() < 3 || count(
                                leftText,
                                leftSpan.key()
                        ) != count(
                                rightText,
                                leftSpan.key()
                        )) {
                    continue;
                }
                double gap = Math.max(
                        0,
                        Math.max(
                                leftSpan.start(),
                                rightSpan.start()
                        ) - Math.min(leftSpan.end(),
                                rightSpan.end()
                        )
                );
                double distance = Math
                        .abs((leftSpan.start() + leftSpan.end() - rightSpan.start() - rightSpan.end()) / 2);
                if (gap > INTERVAL_GAP_SEC && distance > MATCH_TOLERANCE_SEC) {
                    continue;
                }
                int x = leftSpan.last();
                int y = rightSpan.last();
                if (y + 1 < right.size() && right.get(y + 1)
                        .start() < left.get(x)
                                .start()) {
                    continue;
                }
                double cost = Math.abs((leftSpan.end() + rightSpan.end()) / 2 - boundary) + .25 * Math.min(
                        distance,
                        2
                );
                candidates.add(
                        new Candidate(
                                cost,
                                -length(leftSpan.key()),
                                x,
                                y,
                                leftSpan.key()
                        )
                );
            }
        }
        return best(candidates);
    }

    private static List<Span> spans(
            List<Token> tokens,
            List<Integer> indexes
    ) {
        List<Span> spans = new ArrayList<>();
        for (int first = 0; first < indexes.size(); first++) {
            for (int size = 1; size <= Math.min(
                    MAX_SPAN_WORDS,
                    indexes.size() - first
            ); size++) {
                List<Integer> ids = indexes.subList(
                        first,
                        first + size
                );
                String key = joined(
                        tokens,
                        ids
                );
                if (length(key) >= MIN_SPAN_CHARACTERS) {
                    spans.add(
                            new Span(
                                    key,
                                    ids.size(),
                                    ids.getLast(),
                                    tokens.get(ids.getFirst())
                                            .start(),
                                    tokens.get(ids.getLast())
                                            .end()
                            )
                    );
                }
            }
        }
        return spans;
    }

    private static int[] best(List<Candidate> candidates) {
        return candidates.stream()
                .min(
                        Comparator.comparingDouble(Candidate::cost)
                                .thenComparingInt(Candidate::size)
                                .thenComparingInt(Candidate::left)
                                .thenComparingInt(Candidate::right)
                                .thenComparing(Candidate::key)
                )
                .map(candidate -> new int[]{candidate.left() + 1, candidate.right() + 1})
                .orElse(null);
    }

    private static int cutByMidpoint(
            List<Token> tokens,
            double boundary
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index)
                    .midpoint() >= boundary) {
                return index;
            }
        }
        return tokens.size();
    }

    /** 겹쳐 세는 구절 출현 횟수. 한쪽에만 더 있으면 반복 발화를 잘못 합칠 수 있어 정렬 근거로 쓰지 않는다. */
    private static int occurrences(
            List<Token> tokens,
            List<String> phrase
    ) {
        List<String> keys = tokens.stream()
                .map(token -> normalized(token.text()))
                .toList();
        int count = 0;
        for (int start = 0; start + phrase.size() <= keys.size(); start++) {
            if (keys.subList(
                    start,
                    start + phrase.size()
            )
                    .equals(phrase)) {
                count++;
            }
        }
        return count;
    }

    /** 겹치지 않게 센 출현 횟수(Python str.count와 같다). */
    private static int count(
            String text,
            String key
    ) {
        int count = 0;
        for (int at = text.indexOf(key); at >= 0; at = text.indexOf(
                key,
                at + key.length()
        )) {
            count++;
        }
        return count;
    }

    private static String joined(
            List<Token> tokens,
            List<Integer> indexes
    ) {
        StringBuilder text = new StringBuilder();
        indexes.forEach(
                index -> text.append(
                        normalized(
                                tokens.get(index)
                                        .text()
                        )
                )
        );
        return text.toString();
    }

    static String normalized(String text) {
        StringBuilder result = new StringBuilder();
        Normalizer.normalize(
                text,
                Normalizer.Form.NFKC
        )
                .toLowerCase(Locale.ROOT)
                .codePoints()
                .filter(TranscriptSeamAligner::alphanumeric)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static boolean alphanumeric(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetter(codePoint) || type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    private static int length(String text) {
        return text.codePointCount(
                0,
                text.length()
        );
    }

    private static void validate(List<Token> tokens) {
        long previous = -1;
        for (Token token : tokens) {
            if (token.startFrame() < 0 || token.endFrame() <= token.startFrame() || token.startFrame() < previous
                    || token.text()
                            .isBlank()) {
                throw invalidData();
            }
            previous = token.startFrame();
        }
    }

    private static RecordingException invalidData() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    private record Candidate(
            double cost,
            int size,
            int left,
            int right,
            String key
    ) {
    }

    private record Span(
            String key,
            int size,
            int last,
            double start,
            double end
    ) {
    }
}
