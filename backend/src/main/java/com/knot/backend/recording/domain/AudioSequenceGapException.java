package com.knot.backend.recording.domain;

import lombok.Getter;

@Getter
public final class AudioSequenceGapException extends RecordingException {
    private final int expectedNextSequence;

    public AudioSequenceGapException(int expectedNextSequence) {
        super(RecordingErrorCode.AUDIO_SEQUENCE_GAP);
        this.expectedNextSequence = expectedNextSequence;
    }
}
