package com.knot.backend.document.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingTitle;
import java.time.Instant;
import java.util.List;

public record DocumentRecordingItemResult(
        long recordingId,
        String title,
        Instant startedAt,
        int recordingDurationSeconds,
        List<DocumentRecordingDocumentResult> documents
) {

    public static DocumentRecordingItemResult of(
            long recordingId,
            Instant startedAt,
            long recordingDurationMillis,
            String starterNickname,
            String publicAiTitle,
            List<DocumentRecordingDocumentResult> documents
    ) {
        return new DocumentRecordingItemResult(
                recordingId,
                RecordingTitle.display(
                        publicAiTitle,
                        starterNickname
                ),
                startedAt,
                Math.toIntExact(recordingDurationMillis / 1000),
                List.copyOf(documents)
        );
    }
}
