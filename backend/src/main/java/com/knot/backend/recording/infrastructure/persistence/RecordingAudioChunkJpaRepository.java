package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface RecordingAudioChunkJpaRepository extends JpaRepository<RecordingAudioChunk, Long> {

    Optional<RecordingAudioChunk> findByRecordingIdAndSequence(
            long recordingId,
            int sequence
    );

    List<RecordingAudioChunk> findAllByRecordingIdOrderBySequence(long recordingId);
}
