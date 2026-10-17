package com.knot.backend.recording.application.dto.result;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import java.util.List;
public record AudioFinalizationInput(
        String storageKey,
        long totalFrames,
        List<AudioManifestPart> parts
) {
    public AudioFinalizationInput {
        parts = List.copyOf(parts);
    }
}
