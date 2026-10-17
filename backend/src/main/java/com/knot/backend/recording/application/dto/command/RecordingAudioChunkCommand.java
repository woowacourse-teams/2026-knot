package com.knot.backend.recording.application.dto.command;

import com.knot.backend.recording.domain.model.AudioChunkDescriptor;

public record RecordingAudioChunkCommand(
        RecordingControlCommand control,
        AudioChunkDescriptor descriptor
) {
}
