package com.knot.backend.recording.presentation.dto.request;

import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RecordingSpeakerCountRequest(
        UUID tabId,
        String controlToken,
        @io.swagger.v3.oas.annotations.media.Schema(type = "integer", minimum = "1", maximum = "10", nullable = true, requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) JsonNode speakerCount
) {
    public RecordingControlCommand toCommand() {
        if (tabId == null || controlToken == null || !controlToken.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return new RecordingControlCommand(
                tabId,
                controlToken
        );
    }

    public Integer count() {
        if (speakerCount == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (speakerCount.isNull()) {
            return null;
        }
        if (!speakerCount.isIntegralNumber() || !speakerCount.canConvertToInt() || speakerCount.asInt() < 1
                || speakerCount.asInt() > 10) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return speakerCount.asInt();
    }

    @Override
    public String toString() {
        return "RecordingSpeakerCountRequest[controlToken=REDACTED]";
    }
}
