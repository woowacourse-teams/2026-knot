package com.knot.backend.recording.application;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingAudioRetentionService {
    private final RecordingAudioDeletionRepository recordingAudioDeletionRepository;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void schedule(long uploadId) {
        Optional<RecordingAudioUpload> upload = recordingAudioDeletionRepository.findUploadForUpdate(uploadId);
        if (upload.isEmpty()) {
            return;
        }
        RecordingAudioUpload audioUpload = upload.orElseThrow();
        Instant now = now();
        if (recordingAudioDeletionRepository.hasNoContentResult(audioUpload.getRecordingId())) {
            scheduleLocked(
                    audioUpload,
                    now
            );
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleNoContent(long recordingId) {
        if (!recordingAudioDeletionRepository.hasNoContentResult(recordingId)) {
            return;
        }
        recordingAudioDeletionRepository.findUploadByRecordingForUpdate(recordingId)
                .ifPresent(
                        upload -> scheduleLocked(
                                upload,
                                now()
                        )
                );
    }

    private void scheduleLocked(
            RecordingAudioUpload upload,
            Instant now
    ) {
        if (!upload.isAudioAvailable() || recordingAudioDeletionRepository.hasTaskForUpload(upload.getId())) {
            return;
        }
        recordingAudioDeletionRepository.saveAndFlush(
                RecordingAudioDeletionTask.request(
                        upload.getId(),
                        upload.getStorageKey(),
                        now
                )
        );
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
