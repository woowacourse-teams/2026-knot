package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import java.util.Optional;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingAudioChunkRepositoryAdapter implements RecordingAudioChunkRepository {
    private final RecordingAudioChunkJpaRepository repository;

    @Override
    public RecordingAudioChunk save(RecordingAudioChunk value) {
        return repository.saveAndFlush(value);
    }

    @Override
    public Optional<RecordingAudioChunk> findByRecordingIdAndSequence(
            long recordingId,
            int sequence
    ) {
        return repository.findByRecordingIdAndSequence(
                recordingId,
                sequence
        );
    }

    @Override
    public List<RecordingAudioChunk> findAllByRecordingIdOrderBySequence(long recordingId) {
        return repository.findAllByRecordingIdOrderBySequence(recordingId);
    }
}
