package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import java.time.Instant;

public record RecordingAudioChunkCompletionResult(
        int sequence,
        RecordingAudioChunkStatus status,
        int verifiedThroughSequence,
        long verifiedThroughFrame,
        long verifiedAudioMillis,
        Instant confirmedAt
) {
}
