package com.knot.backend.recording.infrastructure.persistence;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
@Repository
@RequiredArgsConstructor
public class RecordingAudioFinalizationRepositoryAdapter implements RecordingAudioFinalizationRepository {
    private final RecordingAudioFinalizationJpaRepository repository;
    @Override
    public RecordingAudioFinalization save(RecordingAudioFinalization job) {
        return repository.saveAndFlush(job);
    }
    @Override
    public Optional<RecordingAudioFinalization> findByRecordingId(long id) {
        return repository.findByRecordingId(id);
    }
    @Override
    public Optional<RecordingAudioFinalization> findByRecordingIdForUpdate(long id) {
        return repository.findWithLockByRecordingId(id);
    }
    @Override
    public List<Long> findUnregisteredSealedRecordingIds(int limit) {
        return repository.findUnregisteredSealedRecordingIds(limit);
    }
    @Override
    public List<Long> findRunnableRecordingIds(
            Instant now,
            int limit
    ) {
        return repository.findRunnableRecordingIds(
                now,
                limit
        );
    }
}
