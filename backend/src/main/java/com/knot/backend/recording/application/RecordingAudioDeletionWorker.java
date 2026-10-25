package com.knot.backend.recording.application;

import com.knot.backend.global.logging.ServerErrorLogger;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingAudioDeletionWorker {
    private final RecordingAudioStorage recordingAudioStorage;
    private final RecordingAudioCleanupStateService cleanupState;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void executeCleanup(long recordingId) {
        Optional<RecordingAudioCleanupStateService.CleanupExecution> claim = cleanupState.claim(recordingId);
        if (claim.isEmpty()) {
            return;
        }
        RecordingAudioCleanupStateService.CleanupExecution execution = claim.orElseThrow();
        try {
            recordingAudioStorage.deleteRecordingPrefix(execution.storagePrefix());
        } catch (RuntimeException exception) {
            cleanupState.finish(
                    execution,
                    false
            );
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Audio prefix deletion deferred recordingId=" + recordingId,
                    exception
            );
            return;
        }
        cleanupState.finish(
                execution,
                true
        );
    }
}
