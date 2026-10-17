package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;

public final class RecordingAudioCaptureExpiredException extends RecordingException {

    public RecordingAudioCaptureExpiredException() {
        super(RecordingErrorCode.RECORDING_ALREADY_ENDED);
    }
}
