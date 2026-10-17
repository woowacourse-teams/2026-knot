package com.knot.backend.recording.domain.repository;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
public interface RecordingAudioFinalizationRepository {
    RecordingAudioFinalization save(RecordingAudioFinalization job);
    Optional<RecordingAudioFinalization> findByRecordingId(long recordingId);
    Optional<RecordingAudioFinalization> findByRecordingIdForUpdate(long recordingId);
    List<Long> findUnregisteredSealedRecordingIds(int limit);
    List<Long> findRunnableRecordingIds(
            Instant now,
            int limit
    );
}
