package com.knot.backend.recording.application.dto.result;

import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;

public enum RecordingDocumentProcessingStatus {
    PROCESSING,
    TRANSCRIPTION_FAILED,
    DOCUMENT_FAILED,
    NO_CONTENT,
    COMPLETED;

    public static RecordingDocumentProcessingStatus from(DocumentGenerationProcessingStatus documents) {
        return switch (documents) {
            case QUEUED, RUNNING -> PROCESSING;
            case FAILED -> DOCUMENT_FAILED;
            case NO_CONTENT -> NO_CONTENT;
            case SUCCEEDED -> COMPLETED;
        };
    }
}
