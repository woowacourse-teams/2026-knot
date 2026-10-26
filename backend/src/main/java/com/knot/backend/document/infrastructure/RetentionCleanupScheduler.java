package com.knot.backend.document.infrastructure;

import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.recording.application.RecordingAudioCleanupStateService;
import com.knot.backend.recording.application.RecordingAudioDeletionWorker;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "knot.retention", name = "enabled", havingValue = "true")
public class RetentionCleanupScheduler {
    private final RecordingAudioDeletionWorker worker;
    private final RetentionCleanupProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final RecordingAudioCleanupRepository audioCleanup;
    private final RecordingAudioCleanupStateService audioCleanupState;

    public RetentionCleanupScheduler(
            RecordingAudioDeletionWorker worker,
            RetentionCleanupProperties properties,
            @Qualifier("retentionCleanupExecutor") ThreadPoolTaskExecutor executor,
            Clock clock,
            RecordingAudioCleanupRepository audioCleanup,
            RecordingAudioCleanupStateService audioCleanupState
    ) {
        this.worker = worker;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.audioCleanup = audioCleanup;
        this.audioCleanupState = audioCleanupState;
    }

    @Scheduled(fixedDelayString = "${knot.retention.poll-interval:1m}")
    public void scheduleAudioCleanup() {
        try {
            scheduleAudioCleanupCandidates();
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "RetentionCleanupScheduler phase=scheduleAudioCleanup-candidates",
                    exception
            );
        }
    }

    private void scheduleAudioCleanupCandidates() {
        for (Long recordingId : audioCleanup.findCandidates(properties.getCandidateLimit())) {
            try {
                audioCleanupState.schedule(recordingId);
            } catch (RuntimeException exception) {
                ServerErrorLogger.record(
                        log,
                        "Audio cleanup scheduling deferred recordingId=" + recordingId,
                        exception
                );
            }
        }
    }

    @Scheduled(fixedDelayString = "${knot.retention.deletion-interval:5s}")
    public void dispatchAudioPrefixDeletion() {
        try {
            for (Long recordingId : audioCleanup.findReady(
                    clock.instant(),
                    properties.getCandidateLimit()
            )) {
                executor.execute(() -> {
                    try {
                        worker.executeCleanup(recordingId);
                    } catch (RuntimeException exception) {
                        ServerErrorLogger.record(
                                log,
                                "Audio cleanup deferred recordingId=" + recordingId,
                                exception
                        );
                    }
                });
            }
        } catch (TaskRejectedException exception) {
            // 다음 poll에서 DB 대기 작업을 다시 조회한다.
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Audio cleanup candidate lookup failed",
                    exception
            );
        }
    }
}
