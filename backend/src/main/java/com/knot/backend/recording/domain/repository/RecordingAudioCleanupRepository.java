package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioCleanup;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecordingAudioCleanupRepository {
    List<Long> findOwned(
            long workspaceId,
            long memberId
    );
    void track(
            long recordingId,
            Instant now
    );
    List<Long> findCandidates(
            Instant now,
            int limit
    );
    RecordingAudioCleanupPolicy policy(
            long recordingId,
            Instant now
    );
    List<Long> findReady(
            Instant now,
            int limit
    );
    Optional<RecordingAudioCleanup> findForUpdate(long recordingId);
}
