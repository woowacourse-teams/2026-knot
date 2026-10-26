package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingTitleGeneration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecordingTitleGenerationRepository {

    RecordingTitleGeneration save(RecordingTitleGeneration generation);

    void recordActivation(Instant now);

    int registerEligible(
            Instant now,
            int limit
    );

    List<Long> findRunnableRecordingIds(
            Instant now,
            int limit
    );

    Optional<RecordingTitleGeneration> findByRecordingIdForUpdate(long recordingId);

    Optional<String> findTranscript(long recordingId);
}
