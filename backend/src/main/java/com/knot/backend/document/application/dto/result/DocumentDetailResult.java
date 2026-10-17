package com.knot.backend.document.application.dto.result;

import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import java.time.Instant;

public record DocumentDetailResult(
        long id,
        long recordingSessionId,
        String topic,
        String title,
        String summary,
        String content,
        DocumentStatus status,
        Instant createdAt,
        Instant archivedAt,
        int recordingDurationSeconds,
        long sourceTranscriptId,
        MyConfirmationState myConfirmationState,
        DocumentConfirmationSummaryResult confirmationSummary,
        com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality sourceAudio
) {
    private static final long MILLIS_PER_SECOND = 1000;

    public DocumentDetailResult withSourceAudio(
            com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality source
    ) {
        return new DocumentDetailResult(
                id,
                recordingSessionId,
                topic,
                title,
                summary,
                content,
                status,
                createdAt,
                archivedAt,
                recordingDurationSeconds,
                sourceTranscriptId,
                myConfirmationState,
                confirmationSummary,
                source
        );
    }

    public static DocumentDetailResult from(DocumentDetailSnapshot snapshot) {
        return new DocumentDetailResult(
                snapshot.id(),
                snapshot.recordingSessionId(),
                snapshot.topic(),
                snapshot.title(),
                snapshot.summary(),
                snapshot.content(),
                snapshot.status(),
                snapshot.createdAt(),
                snapshot.archivedAt(),
                Math.toIntExact(snapshot.recordingDurationMillis() / MILLIS_PER_SECOND),
                snapshot.sourceTranscriptId(),
                MyConfirmationState.resolve(
                        snapshot.requiredByMe(),
                        snapshot.confirmedAtByMe()
                ),
                snapshot.confirmationSummary(),
                null
        );
    }
}
