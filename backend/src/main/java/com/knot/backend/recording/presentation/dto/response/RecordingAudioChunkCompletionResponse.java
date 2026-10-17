package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingAudioChunkCompletionResult;
import java.time.Instant;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;

public record RecordingAudioChunkCompletionResponse(
        int sequence,
        RecordingAudioChunkStatus status,
        int verifiedThroughSequence,
        long verifiedThroughFrame,
        long verifiedAudioMillis,
        Instant confirmedAt
) {

    public static RecordingAudioChunkCompletionResponse from(RecordingAudioChunkCompletionResult result) {
        return new RecordingAudioChunkCompletionResponse(
                result.sequence(),
                result.status(),
                result.verifiedThroughSequence(),
                result.verifiedThroughFrame(),
                result.verifiedAudioMillis(),
                result.confirmedAt()
        );
    }
}
