package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioStream;
import java.util.Optional;

public interface RecordingAudioStreamRepository {

    RecordingAudioStream save(RecordingAudioStream value);

    Optional<RecordingAudioStream> findByRecordingIdForUpdate(long recordingId);

    Optional<RecordingAudioStream> findByRecordingId(long recordingId);
}
