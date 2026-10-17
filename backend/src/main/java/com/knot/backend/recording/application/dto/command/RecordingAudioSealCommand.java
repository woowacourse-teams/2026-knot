package com.knot.backend.recording.application.dto.command;

import com.knot.backend.recording.domain.model.AudioChunkDescriptor;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.List;

public record RecordingAudioSealCommand(
        int lastSequence,
        long totalFrameCount,
        List<AudioChunkDescriptor> pendingDescriptors
) {
    public RecordingAudioSealCommand {
        if (lastSequence < 0 || totalFrameCount < 0 || totalFrameCount > 115200000L || pendingDescriptors == null
                || pendingDescriptors.stream()
                        .anyMatch(java.util.Objects::isNull)) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_SEAL);
        }
        pendingDescriptors = List.copyOf(pendingDescriptors);
    }
}
