package com.knot.backend.recording.presentation.dto.request;
import tools.jackson.databind.JsonNode;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
public record RecordingSpeakerNameRequest(
        @io.swagger.v3.oas.annotations.media.Schema(type = "string", minLength = 1, maxLength = 50, requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) JsonNode displayName
) {
    public String name() {
        if (displayName == null || !displayName.isString()) {
            throw new RecordingException(RecordingErrorCode.INVALID_SPEAKER_DISPLAY_NAME);
        }
        return displayName.asString();
    }
}
