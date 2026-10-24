package com.knot.backend.recording.application;

import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.recording.application.dto.result.RecordingAudioDeletionExecution;
import com.knot.backend.recording.domain.repository.RecordingAudioDeletionRepository;
import com.knot.backend.recording.domain.model.RecordingAudioDeletionTask;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingAudioDeletionStateService {
    private final RecordingAudioDeletionRepository recordingAudioDeletionRepository;
    private final RetentionCleanupProperties retentionCleanupProperties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<RecordingAudioDeletionExecution> claim(long taskId) {
        Optional<RecordingAudioDeletionTask> task = lockUploadAndTask(taskId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        Instant now = now();
        RecordingAudioDeletionTask deletionTask = task.orElseThrow();
        RecordingAudioUpload upload = recordingAudioDeletionRepository.findUploadForUpdate(deletionTask.getUploadId())
                .orElseThrow();
        if (!recordingAudioDeletionRepository.hasNoContentResult(upload.getRecordingId())) {
            return Optional.empty();
        }
        if (!deletionTask.isReadyAt(now)) {
            return Optional.empty();
        }
        deletionTask.claim(
                now,
                now.plus(retentionCleanupProperties.getExecutionLease())
        );
        return Optional.of(
                new RecordingAudioDeletionExecution(
                        taskId,
                        deletionTask.getAttemptCount(),
                        deletionTask.getStorageKey()
                )
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void succeed(
            long taskId,
            int attempt
    ) {
        Optional<RecordingAudioDeletionTask> task = lockUploadAndTask(taskId);
        if (task.isEmpty()) {
            return;
        }
        RecordingAudioDeletionTask deletionTask = task.orElseThrow();
        Instant now = now();
        deletionTask.recordSuccess(
                attempt,
                now
        );
        if (deletionTask.isSucceeded()) {
            recordingAudioDeletionRepository.findUploadForUpdate(deletionTask.getUploadId())
                    .orElseThrow()
                    .recordDeleted(now);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(
            long taskId,
            int attempt
    ) {
        lockUploadAndTask(taskId).ifPresent(
                deletionTask -> deletionTask.recordStorageFailure(
                        attempt,
                        now()
                )
        );
    }

    private Optional<RecordingAudioDeletionTask> lockUploadAndTask(long taskId) {
        Optional<RecordingAudioDeletionTask> task = recordingAudioDeletionRepository.findTask(taskId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        Optional<RecordingAudioUpload> upload = recordingAudioDeletionRepository.findUploadForUpdate(
                task.orElseThrow()
                        .getUploadId()
        );
        if (upload.isEmpty()) {
            return Optional.empty();
        }
        return recordingAudioDeletionRepository.findTaskForUpdate(taskId);
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
