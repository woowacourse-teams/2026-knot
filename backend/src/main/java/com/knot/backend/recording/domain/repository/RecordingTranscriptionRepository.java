package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingTranscription;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecordingTranscriptionRepository {
    RecordingTranscription saveAndFlush(RecordingTranscription transcription);
    Optional<RecordingTranscription> findByRecordingSessionId(long recordingSessionId);
    List<RecordingTranscription> findAllByRecordingSessionIdIn(Collection<Long> recordingSessionIds);
    Optional<RecordingTranscription> findByRecordingSessionIdForUpdate(long recordingSessionId);
    Optional<RecordingTranscription> findProcessing();
    Optional<RecordingTranscription> findNextQueuedForUpdate(java.time.Instant now);
    Optional<RecordingTranscription> findProcessingForUpdate();
    void lockAdmission();
}
