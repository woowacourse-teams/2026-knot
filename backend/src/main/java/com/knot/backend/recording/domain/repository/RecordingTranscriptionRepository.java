package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingTranscription;

import java.util.Optional;

public interface RecordingTranscriptionRepository {
    RecordingTranscription saveAndFlush(RecordingTranscription transcription);
    Optional<RecordingTranscription> findByRecordingSessionId(long recordingSessionId);
    Optional<RecordingTranscription> findByRecordingSessionIdForUpdate(long recordingSessionId);
    Optional<RecordingTranscription> findProcessing();
    Optional<RecordingTranscription> findNextQueuedForUpdate(java.time.Instant now);
    Optional<RecordingTranscription> findProcessingForUpdate();
    void lockAdmission();
}
