package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

public record RecordingDetailResult(
        RecordingStatus status,
        long elapsedMillis,
        Instant endedAt,
        RecordingEndReason endReason,
        RecordingAudioUploadStatus audioUploadStatus,
        UUID transcriptionAttemptId,
        TranscriptionStatus transcriptionStatus,
        String transcriptionFailureCode,
        DocumentGenerationSummaryResult documentGeneration,
        com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality sourceAudio
) {

    public RecordingDetailResult withSourceAudio(
            com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality source
    ) {
        return new RecordingDetailResult(
                status,
                elapsedMillis,
                endedAt,
                endReason,
                audioUploadStatus,
                transcriptionAttemptId,
                transcriptionStatus,
                transcriptionFailureCode,
                documentGeneration,
                source
        );
    }

    public static RecordingDetailResult of(
            RecordingDetailSnapshot snapshot,
            RecordingTranscriptionSnapshot transcription,
            DocumentGenerationSummaryResult documentGeneration,
            Instant serverNow
    ) {
        return new RecordingDetailResult(
                snapshot.status(),
                elapsedMillis(
                        snapshot,
                        serverNow
                ),
                snapshot.endedAt(),
                snapshot.endReason(),
                snapshot.audioUploadStatus(),
                transcription == null ? null : transcription.attemptId(),
                transcription == null ? null : transcription.status(),
                transcription == null ? null : transcription.failureCode(),
                documentGeneration,
                null
        );
    }

    private static long elapsedMillis(
            RecordingDetailSnapshot snapshot,
            Instant serverNow
    ) {
        if (snapshot.status() != RecordingStatus.RECORDING) {
            return snapshot.accumulatedRecordingMillis();
        }
        long currentIntervalMillis = Duration.between(
                snapshot.currentIntervalStartedAt(),
                serverNow
        )
                .toMillis();
        return Math.min(
                snapshot.accumulatedRecordingMillis() + Math.max(
                        0L,
                        currentIntervalMillis
                ),
                RecordingSession.MAX_RECORDING_MILLIS
        );
    }
}
