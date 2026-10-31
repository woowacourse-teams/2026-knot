package com.knot.backend.recording.application.dto.command;

import java.util.UUID;

public record RecordingReconnectCommand(
        UUID previousTabId,
        String previousControlToken,
        UUID tabId,
        String controlToken
) {

    @Override
    public String toString() {
        return "RecordingReconnectCommand[previousControlToken=REDACTED, controlToken=REDACTED]";
    }
}
