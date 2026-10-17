package com.knot.backend.document.infrastructure;

import com.knot.backend.recording.application.RecordingAudioCleanupStateService;

import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.document.application.DocumentRetentionCleanupService;
import com.knot.backend.document.domain.model.DocumentRetentionCandidate;
import com.knot.backend.document.domain.repository.DocumentRetentionRepository;
import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.recording.application.RecordingAudioDeletionWorker;
import com.knot.backend.recording.application.RecordingAudioRetentionService;
import com.knot.backend.recording.domain.repository.RecordingAudioDeletionRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "knot.retention", name = "enabled", havingValue = "true")
public class RetentionCleanupScheduler {
    private final DocumentRetentionRepository documents;
    private final DocumentRetentionCleanupService cleanup;
    private final RecordingAudioDeletionRepository audio;
    private final RecordingAudioRetentionService audioRetention;
    private final RecordingAudioDeletionWorker worker;
    private final RetentionCleanupProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final RecordingAudioCleanupRepository audioCleanup;
    private final RecordingAudioCleanupStateService audioCleanupState;

    public RetentionCleanupScheduler(
            DocumentRetentionRepository documents,
            DocumentRetentionCleanupService cleanup,
            RecordingAudioDeletionRepository audio,
            RecordingAudioRetentionService audioRetention,
            RecordingAudioDeletionWorker worker,
            RetentionCleanupProperties properties,
            @Qualifier("retentionCleanupExecutor") ThreadPoolTaskExecutor executor,
            Clock clock,
            RecordingAudioCleanupRepository audioCleanup,
            RecordingAudioCleanupStateService audioCleanupState
    ) {
        this.documents = documents;
        this.cleanup = cleanup;
        this.audio = audio;
        this.audioRetention = audioRetention;
        this.worker = worker;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.audioCleanup = audioCleanup;
        this.audioCleanupState = audioCleanupState;
    }

    @Scheduled(fixedDelayString = "${knot.retention.poll-interval:1m}")
    public void cleanupExpiredData() {
        try {
            cleanupExpiredDataCandidates();
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "RetentionCleanupScheduler phase=cleanupExpiredData-candidates",
                    exception
            );
        }
    }

    private void cleanupExpiredDataCandidates() {
        for (DocumentRetentionCandidate candidate : documents.findCandidates(
                clock.instant(),
                properties.getCandidateLimit()
        )) {
            try {
                cleanup.cleanup(
                        candidate.getWorkspaceId(),
                        candidate.getRecordingId(),
                        candidate.getBatchId()
                );
            } catch (RuntimeException exception) {
                ServerErrorLogger.record(
                        log,
                        "Document retention deferred workspaceId=" + candidate.getWorkspaceId() + " batchId="
                                + candidate.getBatchId() + " phase=cleanup",
                        exception
                );
            }
        }
        for (Long recordingId : audioCleanup.findCandidates(
                clock.instant(),
                properties.getCandidateLimit()
        )) {
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
        for (Long uploadId : audio.findRetentionCandidates(
                clock.instant()
                        .minus(Duration.ofDays(30)),
                properties.getCandidateLimit()
        )) {
            try {
                audioRetention.schedule(uploadId);
            } catch (RuntimeException exception) {
                ServerErrorLogger.record(
                        log,
                        "Audio retention deferred uploadId=" + uploadId + " phase=schedule",
                        exception
                );
            }
        }
    }

    @Scheduled(fixedDelayString = "${knot.retention.deletion-interval:5s}")
    public void dispatchAudioDeletion() {
        try {
            dispatchAudioDeletionCandidates();
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "RetentionCleanupScheduler phase=dispatchAudioDeletion-candidates",
                    exception
            );
        }
    }

    private void dispatchAudioDeletionCandidates() {
        for (Long taskId : audio.findReadyTasks(
                clock.instant(),
                properties.getCandidateLimit()
        )) {
            try {
                executor.execute(() -> execute(taskId));
            } catch (TaskRejectedException exception) {
                return;
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

    private void execute(long taskId) {
        try {
            worker.execute(taskId);
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Audio result deferred taskId=" + taskId + " phase=execute",
                    exception
            );
        }
    }
}
