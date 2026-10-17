package com.knot.backend.recording.domain;

public final class AudioStorageAccessDeniedException extends RecordingException {
    public AudioStorageAccessDeniedException(Throwable cause) {
        super(
                RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                cause
        );
    }
}
