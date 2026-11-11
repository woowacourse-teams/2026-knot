package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioUploadStatus;
import com.knot.backend.recording.domain.model.RecordingEndReason;
import com.knot.backend.recording.domain.model.RecordingStatus;
import java.time.Instant;

record RecordingDetailRow(
        long memberId,
        RecordingStatus status,
        Instant currentIntervalStartedAt,
        long accumulatedRecordingMillis,
        Instant endedAt,
        RecordingEndReason endReason,
        RecordingAudioUploadStatus audioUploadStatus,
        Integer speakerCount
) {
}
