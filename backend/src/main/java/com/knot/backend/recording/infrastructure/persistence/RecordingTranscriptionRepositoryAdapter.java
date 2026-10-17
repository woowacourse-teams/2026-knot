package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.model.RecordingTranscription;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionRepository;
import com.knot.backend.recording.domain.model.TranscriptionStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingTranscriptionRepositoryAdapter implements RecordingTranscriptionRepository {
    private final RecordingTranscriptionJpaRepository recordingTranscriptionJpaRepository;

    @Override
    public RecordingTranscription saveAndFlush(RecordingTranscription transcription) {
        return recordingTranscriptionJpaRepository.saveAndFlush(transcription);
    }

    @Override
    public Optional<RecordingTranscription> findByRecordingSessionId(long recordingSessionId) {
        return recordingTranscriptionJpaRepository.findByRecordingSessionId(recordingSessionId);
    }

    @Override
    public Optional<RecordingTranscription> findByRecordingSessionIdForUpdate(long recordingSessionId) {
        return recordingTranscriptionJpaRepository.findWithLockByRecordingSessionId(recordingSessionId);
    }

    @Override
    public Optional<RecordingTranscription> findProcessing() {
        return recordingTranscriptionJpaRepository.findByStatus(TranscriptionStatus.PROCESSING);
    }

    @Override
    public Optional<RecordingTranscription> findNextQueuedForUpdate(java.time.Instant now) {
        return recordingTranscriptionJpaRepository.findNextQueuedForUpdate(now);
    }

    @Override
    public Optional<RecordingTranscription> findProcessingForUpdate() {
        return recordingTranscriptionJpaRepository.findProcessingForUpdate();
    }

    @Override
    public void lockAdmission() {
        recordingTranscriptionJpaRepository.lockAdmission();
    }
}
