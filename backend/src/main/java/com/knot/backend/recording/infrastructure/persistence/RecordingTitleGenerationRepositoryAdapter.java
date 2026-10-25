package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingTitleGeneration;
import com.knot.backend.recording.domain.repository.RecordingTitleGenerationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingTitleGenerationRepositoryAdapter implements RecordingTitleGenerationRepository {
    private final RecordingTitleGenerationJpaRepository repository;

    @Override
    public RecordingTitleGeneration save(RecordingTitleGeneration generation) {
        return repository.saveAndFlush(generation);
    }

    @Override
    public int registerEligible(
            Instant now,
            int limit
    ) {
        return repository.registerEligible(
                now,
                limit
        );
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

    @Override
    public Optional<RecordingTitleGeneration> findByRecordingIdForUpdate(long recordingId) {
        return repository.findWithLockByRecordingId(recordingId);
    }

    @Override
    public Optional<String> findTranscript(long recordingId) {
        return repository.findTranscript(recordingId);
    }
}
