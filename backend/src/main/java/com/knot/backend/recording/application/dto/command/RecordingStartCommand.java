package com.knot.backend.recording.application.dto.command;

import com.knot.backend.recording.domain.model.RecordingAudioMode;
import java.util.UUID;

public record RecordingStartCommand(
        UUID requestId,
        UUID tabId,
        String controlToken,
        RecordingAudioMode audioMode
) {

    public RecordingStartCommand {
        audioMode = java.util.Objects.requireNonNullElse(
                audioMode,
                RecordingAudioMode.FINAL_WEBM_V1
        );
    }

    public RecordingStartCommand(
            UUID requestId,
            UUID tabId,
            String controlToken
    ) {
        this(
                requestId,
                tabId,
                controlToken,
                RecordingAudioMode.FINAL_WEBM_V1
        );
    }

    @Override
    public String toString() {
        return "RecordingStartCommand[controlToken=REDACTED]";
    }
}
