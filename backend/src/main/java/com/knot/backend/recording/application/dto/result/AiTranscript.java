package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.TranscriptUtterance;
import java.util.ArrayList;
import java.util.List;

public record AiTranscript(
        int schemaVersion,
        String rawWhisperJson,
        List<Utterance> utterances,
        AiWindowEvidence window
) {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    public AiTranscript {
        utterances = List.copyOf(utterances);
    }

    public AiTranscript(
            int schemaVersion,
            String rawWhisperJson,
            List<Utterance> utterances
    ) {
        this(
                schemaVersion,
                rawWhisperJson,
                utterances,
                null
        );
    }

    public List<TranscriptUtterance> toUtterances() {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        validateEmptySuccess();
        List<TranscriptUtterance> converted = new ArrayList<>();
        for (int ord = 0; ord < utterances.size(); ord++) {
            converted.add(
                    utterances.get(ord)
                            .toUtterance(ord)
            );
        }
        return converted;
    }

    private void validateEmptySuccess() {
        if (!utterances.isEmpty()) {
            return;
        }
        try {
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().rebuild()
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build()
                    .readTree(rawWhisperJson);
            if (root == null || !root.path("transcription")
                    .isArray()) {
                throw new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
            }
            for (tools.jackson.databind.JsonNode segment : root.path("transcription")) {
                tools.jackson.databind.JsonNode text = segment.path("text");
                if (!text.isString() || text.asString()
                        .codePoints()
                        .anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
                    throw new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
                }
            }
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AI_INVALID_RESPONSE,
                    exception
            );
        }
    }

    public record Utterance(
            int ord,
            String speakerLabel,
            double startSec,
            double endSec,
            String text
    ) {

        private TranscriptUtterance toUtterance(int expectedOrd) {
            if (ord != expectedOrd) {
                throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
            }
            return new TranscriptUtterance(
                    speakerLabel,
                    startSec,
                    endSec,
                    text
            );
        }
    }
}
