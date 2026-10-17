package com.knot.backend.recording.application.dto.result;

public record RecordingAudioChunkConfirmation(
        String storageKey,
        RecordingAudioChunkCompletionResult completed
) {
}
