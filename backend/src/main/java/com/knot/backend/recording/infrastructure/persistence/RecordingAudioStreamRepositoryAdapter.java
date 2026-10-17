package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingAudioStreamRepositoryAdapter implements RecordingAudioStreamRepository {
    private final RecordingAudioStreamJpaRepository repository;

    @Override
    public RecordingAudioStream save(RecordingAudioStream value) {
        return repository.saveAndFlush(value);
    }

    @Override
    public Optional<RecordingAudioStream> findByRecordingIdForUpdate(long recordingId) {
        return repository.findWithLockByRecordingId(recordingId);
    }

    @Override
    public Optional<RecordingAudioStream> findByRecordingId(long recordingId) {
        return repository.findByRecordingId(recordingId);
    }
}
