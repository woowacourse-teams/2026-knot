package com.knot.backend.recording.domain;

import com.knot.backend.global.exception.ProjectException;

public class RecordingException extends ProjectException {

    public RecordingException(RecordingErrorCode errorCode) {
        super(errorCode);
    }

    public RecordingException(
            RecordingErrorCode errorCode,
            Throwable cause
    ) {
        super(
                errorCode,
                cause
        );
    }
}
