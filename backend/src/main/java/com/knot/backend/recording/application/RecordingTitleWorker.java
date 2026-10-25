package com.knot.backend.recording.application;

import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.recording.application.dto.result.RecordingTitleTask;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "knot.recording-title.worker", name = "enabled", havingValue = "true")
public class RecordingTitleWorker {
    private static final String INTERNAL_FAILURE = "INTERNAL";

    private final RecordingTitleGenerationService recordingTitleGenerationService;
    private final RecordingTitleGenerator recordingTitleGenerator;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService execution = Executors.newSingleThreadExecutor(
            Thread.ofPlatform()
                    .daemon()
                    .name("recording-title-generation")
                    .factory()
    );

    public RecordingTitleWorker(
            RecordingTitleGenerationService recordingTitleGenerationService,
            RecordingTitleGenerator recordingTitleGenerator
    ) {
        this.recordingTitleGenerationService = recordingTitleGenerationService;
        this.recordingTitleGenerator = recordingTitleGenerator;
    }

    @Scheduled(fixedDelayString = "${knot.recording-title.worker.poll-interval:PT5S}")
    public void poll() {
        if (!running.compareAndSet(
                false,
                true
        )) {
            return;
        }
        try {
            execution.execute(this::runInBackground);
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    public void runOnce() {
        try {
            recordingTitleGenerationService.registerEligible();
            for (Long recordingId : recordingTitleGenerationService.findRunnable()) {
                claimAndProcess(recordingId);
            }
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "RecordingTitleWorker phase=poll",
                    exception
            );
        }
    }

    public void process(RecordingTitleTask task) {
        try {
            recordingTitleGenerationService.succeed(
                    task,
                    recordingTitleGenerator.generate(task.transcript())
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Recording title generation failed recordingId=" + task.recordingId() + " attempt="
                            + task.attempt(),
                    exception
            );
            recordFailure(
                    task,
                    failureCode(exception)
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        execution.shutdownNow();
    }

    private void runInBackground() {
        try {
            runOnce();
        } finally {
            running.set(false);
        }
    }

    private void claimAndProcess(long recordingId) {
        try {
            recordingTitleGenerationService.claim(recordingId)
                    .ifPresent(this::process);
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Recording title claim deferred recordingId=" + recordingId,
                    exception
            );
        }
    }

    private void recordFailure(
            RecordingTitleTask task,
            String failureCode
    ) {
        try {
            recordingTitleGenerationService.fail(
                    task,
                    failureCode
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Recording title failure persistence deferred recordingId=" + task.recordingId(),
                    exception
            );
        }
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof ProjectException projectException) {
            return projectException.getErrorCode()
                    .getCode();
        }
        return INTERNAL_FAILURE;
    }
}
