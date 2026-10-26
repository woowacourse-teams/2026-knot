package com.knot.backend.recording.application.dto.result;

import com.knot.backend.document.application.dto.result.DocumentGenerationStatusResult;
import java.util.List;

public record RecordingDocumentProcessingResult(
        RecordingDocumentProcessingStatus status,
        boolean retryable,
        List<Long> retryableJobIds
) {

    public RecordingDocumentProcessingResult {
        retryableJobIds = List.copyOf(retryableJobIds);
    }

    public static RecordingDocumentProcessingResult processing() {
        return new RecordingDocumentProcessingResult(
                RecordingDocumentProcessingStatus.PROCESSING,
                false,
                List.of()
        );
    }

    public static RecordingDocumentProcessingResult transcriptionFailed(boolean retryable) {
        return new RecordingDocumentProcessingResult(
                RecordingDocumentProcessingStatus.TRANSCRIPTION_FAILED,
                retryable,
                List.of()
        );
    }

    public static RecordingDocumentProcessingResult from(DocumentGenerationStatusResult documents) {
        RecordingDocumentProcessingStatus status = RecordingDocumentProcessingStatus.from(documents.status());
        if (status != RecordingDocumentProcessingStatus.DOCUMENT_FAILED) {
            return new RecordingDocumentProcessingResult(
                    status,
                    false,
                    List.of()
            );
        }
        return new RecordingDocumentProcessingResult(
                status,
                !documents.retryableJobIds()
                        .isEmpty(),
                documents.retryableJobIds()
        );
    }
}
