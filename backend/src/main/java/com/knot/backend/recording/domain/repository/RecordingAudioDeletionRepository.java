package com.knot.backend.recording.domain.repository;

import com.knot.backend.recording.domain.model.RecordingAudioDeletionTask;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecordingAudioDeletionRepository {

    List<Long> findNoContentCandidates(int limit);

    List<Long> findReadyTasks(
            Instant now,
            int limit
    );

    Optional<RecordingAudioUpload> findUploadForUpdate(long uploadId);

    Optional<RecordingAudioUpload> findUploadByRecordingForUpdate(long recordingId);

    Optional<RecordingAudioDeletionTask> findTask(long taskId);

    Optional<RecordingAudioDeletionTask> findTaskForUpdate(long taskId);

    boolean hasTaskForUpload(long uploadId);

    boolean hasNoContentResult(long recordingId);

    void saveAndFlush(RecordingAudioDeletionTask task);
}
