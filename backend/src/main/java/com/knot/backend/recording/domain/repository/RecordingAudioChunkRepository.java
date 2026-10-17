package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import java.util.Optional;
import java.util.List;

public interface RecordingAudioChunkRepository {

    RecordingAudioChunk save(RecordingAudioChunk value);

    Optional<RecordingAudioChunk> findByRecordingIdAndSequence(
            long recordingId,
            int sequence
    );

    List<RecordingAudioChunk> findAllByRecordingIdOrderBySequence(long recordingId);
}
