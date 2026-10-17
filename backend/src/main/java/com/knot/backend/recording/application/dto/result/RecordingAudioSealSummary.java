package com.knot.backend.recording.application.dto.result;

import java.time.Instant;

public record RecordingAudioSealSummary(
        String audioSaveStatus,
        int expectedLastSequence,
        long totalFrameCount,
        boolean tailUnknown,
        Instant acceptUntil
) {
}
