package com.knot.backend.recording.presentation.dto.response;

import com.knot.backend.recording.application.dto.result.RecordingAudioSaveResult;
import java.time.Instant;
import com.knot.backend.recording.application.dto.result.AudioFrameRange;
import java.util.List;

public record RecordingAudioSaveResponse(
        String audioSaveStatus,
        int verifiedThroughSequence,
        long verifiedThroughFrame,
        long verifiedAudioMillis,
        List<AudioFrameRange> verifiedRanges,
        List<AudioFrameRange> missingRanges,
        boolean tailUnknown,
        Integer expectedLastSequence,
        Instant acceptUntil,
        String failureCode,
        boolean canRetry,
        Long jobId,
        String quality
) {

    public static RecordingAudioSaveResponse from(RecordingAudioSaveResult result) {
        return new RecordingAudioSaveResponse(
                result.audioSaveStatus(),
                result.verifiedThroughSequence(),
                result.verifiedThroughFrame(),
                result.verifiedAudioMillis(),
                result.verifiedRanges(),
                result.missingRanges(),
                result.tailUnknown(),
                result.expectedLastSequence(),
                result.acceptUntil(),
                result.failureCode(),
                result.canRetry(),
                result.jobId(),
                result.quality()
        );
    }
}
