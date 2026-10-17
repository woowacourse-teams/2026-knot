package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.RemoteObservation;
import java.time.Instant;
import java.util.UUID;

public record RecordingTranscriptionExecution(
        long workspaceId,
        long recordingId,
        UUID attemptId,
        String storageKey,
        String storageVersionId,
        String contentType,
        long contentLength,
        Instant startedAt,
        UUID ownerToken,
        long claimEpoch,
        Instant executionDeadline,
        RemoteObservation remoteObservation,
        Integer speakerCount
) {
}
