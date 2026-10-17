package com.knot.backend.recording.domain;
public final class AudioFinalizationLeaseLostException extends RecordingException {
    public AudioFinalizationLeaseLostException() {
        super(RecordingErrorCode.AUDIO_SAVE_OWNERSHIP_LOST);
    }
}
