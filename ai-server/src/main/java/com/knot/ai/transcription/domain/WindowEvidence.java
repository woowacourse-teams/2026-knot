package com.knot.ai.transcription.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record WindowEvidence(
        int schemaVersion,
        String pipelineVersion,
        String inputManifestSha256,
        long inputStartFrame,
        long inputEndFrame,
        String modelVersion,
        List<Token> tokens,
        List<Speaker> speakers,
        List<Observation> observations
) {
    private static final int EMBEDDING_DIMENSIONS = 512;
    private static final int MAX_OBSERVATIONS = 2_000;

    public WindowEvidence(
            int schemaVersion,
            String pipelineVersion,
            String inputManifestSha256,
            long inputStartFrame,
            long inputEndFrame,
            String modelVersion,
            List<Token> tokens,
            List<Speaker> speakers
    ) {
        this(
                schemaVersion,
                pipelineVersion,
                inputManifestSha256,
                inputStartFrame,
                inputEndFrame,
                modelVersion,
                tokens,
                speakers,
                List.of()
        );
    }

    public WindowEvidence {
        new WindowInput(
                pipelineVersion,
                inputManifestSha256,
                inputStartFrame,
                inputEndFrame
        );
        if (schemaVersion != 1 || modelVersion == null || modelVersion.isBlank() || modelVersion.length() > 256
                || tokens == null || speakers == null || tokens.size() > 20000 || speakers.size() > 64) {
            throw invalid();
        }
        long frames = inputEndFrame - inputStartFrame;
        Set<String> ids = new HashSet<>();
        for (Speaker speaker : speakers) {
            if (speaker == null || !ids.add(speaker.speakerId()) || speaker.voicedFrames() > frames) {
                throw invalid();
            }
        }
        long previous = -1;
        for (Token token : tokens) {
            if (token == null || token.startFrame() < previous || token.endFrame() > frames
                    || !ids.contains(token.speakerId())) {
                throw invalid();
            }
            previous = token.startFrame();
        }
        if (observations == null) {
            observations = List.of();
        }
        if (observations.size() > MAX_OBSERVATIONS) {
            throw invalid();
        }
        for (Observation observation : observations) {
            if (observation == null || observation.endFrame() > frames || !ids.contains(observation.speakerId())) {
                throw invalid();
            }
        }
        tokens = List.copyOf(tokens);
        speakers = List.copyOf(speakers);
        observations = List.copyOf(observations);
    }

    private static TranscriptionException invalid() {
        return new TranscriptionException(TranscriptionErrorCode.INVALID_RESULT);
    }

    /** segment는 같은 Whisper segment의 단어를 묶는 번호다. BE가 문장 경계를 다시 계산할 때 쓴다. */
    public record Token(
            long startFrame,
            long endFrame,
            String text,
            String speakerId,
            Integer segment
    ) {
        public Token(
                long startFrame,
                long endFrame,
                String text,
                String speakerId
        ) {
            this(
                    startFrame,
                    endFrame,
                    text,
                    speakerId,
                    null
            );
        }

        public Token {
            if (startFrame < 0 || endFrame <= startFrame || text == null || text.isBlank() || speakerId == null
                    || speakerId.isBlank() || (segment != null && segment < 0)) {
                throw invalid();
            }
        }
    }

    public record Speaker(
            String speakerId,
            List<Double> embedding,
            long voicedFrames
    ) {
        public Speaker {
            if (speakerId == null || speakerId.isBlank() || embedding == null || voicedFrames < 0
                    || (!embedding.isEmpty() && embedding.size() != 512) || embedding.stream()
                            .anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw invalid();
            }
            embedding = List.copyOf(embedding);
        }
    }

    /** BE가 녹음 종료 시 다시 묶는 5초 이하 화자 근거 조각. 시간은 입력 WAV 시작이 0인 frame이다. */
    public record Observation(
            long startFrame,
            long endFrame,
            String speakerId,
            List<Double> embedding
    ) {
        public Observation {
            if (startFrame < 0 || endFrame <= startFrame || speakerId == null || speakerId.isBlank()
                    || embedding == null || embedding.size() != EMBEDDING_DIMENSIONS || embedding.stream()
                            .anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw invalid();
            }
            embedding = List.copyOf(embedding);
        }
    }
}
