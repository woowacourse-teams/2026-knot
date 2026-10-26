package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecordingMeetingSummaryRepository {

    void recordActivation(Instant now);

    int registerEligible(
            Instant now,
            int limit
    );

    List<Long> findRunnable(
            Instant now,
            int limit
    );

    Optional<RecordingMeetingSummary> findByRecordingIdForUpdate(long recordingId);

    Optional<RecordingMeetingSummary> findByRecordingId(long recordingId);

    Optional<Long> findWorkspaceId(long recordingId);

    boolean isReadable(long recordingId);

    Optional<String> findUsableTranscript(
            long recordingId,
            long transcriptId
    );
}
