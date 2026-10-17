package com.knot.backend.recording.application.dto.result;
import java.util.List;
public record RecordingSourceAudioQuality(
        String quality,
        List<AudioFrameRange> missingRanges,
        boolean tailUnknown
) {
    public RecordingSourceAudioQuality {
        missingRanges = List.copyOf(missingRanges);
    }
}
