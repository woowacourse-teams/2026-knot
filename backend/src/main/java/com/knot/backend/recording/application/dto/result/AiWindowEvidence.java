package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.TranscriptionWindowShape;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record AiWindowEvidence(
        int schemaVersion,
        String pipelineVersion,
        String inputManifestSha256,
        long inputStartFrame,
        long inputEndFrame,
        String modelVersion,
        List<Token> tokens,
        List<Speaker> speakers
) {
    private static final int MAX_TOKENS = 20_000;
    private static final int MAX_SPEAKERS = 64;
    private static final int EMBEDDING_DIMENSIONS = 512;

    public AiWindowEvidence {
        if (schemaVersion != 1 || !boundedText(
                pipelineVersion,
                64
        ) || !boundedText(
                modelVersion,
                128
        ) || inputManifestSha256 == null || !inputManifestSha256.matches("[0-9a-f]{64}") || inputStartFrame < 0
                || inputEndFrame <= inputStartFrame
                || inputEndFrame - inputStartFrame > TranscriptionWindowShape.MAX_INPUT_FRAMES || tokens == null
                || speakers == null || tokens.size() > MAX_TOKENS || speakers.size() > MAX_SPEAKERS) {
            throw invalidData();
        }
        long span = inputEndFrame - inputStartFrame;
        Set<String> speakerIds = new HashSet<>();
        for (Speaker speaker : speakers) {
            if (speaker == null || !speakerIds.add(speaker.speakerId()) || speaker.voicedFrames() > span) {
                throw invalidData();
            }
        }
        long previousStart = -1;
        for (Token token : tokens) {
            if (token == null || token.startFrame() < previousStart || token.endFrame() > span
                    || !speakerIds.contains(token.speakerId())) {
                throw invalidData();
            }
            previousStart = token.startFrame();
        }
        tokens = List.copyOf(tokens);
        speakers = List.copyOf(speakers);
    }

    public record Token(
            long startFrame,
            long endFrame,
            String text,
            String speakerId
    ) {
        public Token {
            if (startFrame < 0 || endFrame < startFrame || text == null || text.isBlank() || text.length() > 10_000
                    || !boundedText(
                            speakerId,
                            100
                    )) {
                throw invalidData();
            }
        }
    }

    public record Speaker(
            String speakerId,
            List<Double> embedding,
            long voicedFrames
    ) {
        public Speaker {
            if (!boundedText(
                    speakerId,
                    100
            ) || embedding == null || voicedFrames < 0
                    || (!embedding.isEmpty() && (voicedFrames == 0 || embedding.size() != EMBEDDING_DIMENSIONS))) {
                throw invalidData();
            }
            double norm = 0;
            for (Double component : embedding) {
                if (component == null || !Double.isFinite(component)) {
                    throw invalidData();
                }
                norm = Math.hypot(
                        norm,
                        component
                );
            }
            if (!embedding.isEmpty() && (!Double.isFinite(norm) || norm == 0)) {
                throw invalidData();
            }
            embedding = List.copyOf(embedding);
        }
    }

    private static boolean boundedText(
            String text,
            int maximumLength
    ) {
        return text != null && !text.isBlank() && text.length() <= maximumLength;
    }

    private static RecordingException invalidData() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
    }
}
