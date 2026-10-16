package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.TranscriptUtterance;
import java.util.ArrayList;
import java.util.List;

public record AiTranscript(
        int schemaVersion,
        String rawWhisperJson,
        List<Utterance> utterances
) {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    public AiTranscript {
        utterances = List.copyOf(utterances);
    }

    public List<TranscriptUtterance> toUtterances() {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        List<TranscriptUtterance> converted = new ArrayList<>();
        for (int ord = 0; ord < utterances.size(); ord++) {
            converted.add(
                    utterances.get(ord)
                            .toUtterance(ord)
            );
        }
        return converted;
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
