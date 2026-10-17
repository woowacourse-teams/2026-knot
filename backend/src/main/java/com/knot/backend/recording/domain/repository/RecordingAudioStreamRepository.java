package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioStream;
import java.util.Optional;
import java.util.List;

public interface RecordingAudioStreamRepository {

    List<Long> findSealedRecordingIds(int limit);

    RecordingAudioStream save(RecordingAudioStream value);

    Optional<RecordingAudioStream> findByRecordingIdForUpdate(long recordingId);

    Optional<RecordingAudioStream> findByRecordingId(long recordingId);
}
