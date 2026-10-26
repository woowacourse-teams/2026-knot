package com.knot.backend.recording.application;

import com.knot.backend.global.exception.ErrorCode;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.global.exception.ProjectException;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.recording.application.dto.result.MeetingSummaryTask;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "knot.meeting-summary.worker", name = "enabled", havingValue = "true")
public class MeetingSummaryWorker {

    private final MeetingSummaryGenerationService service;
    private final MeetingSummaryGenerator generator;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform()
                    .daemon()
                    .name("meeting-summary")
                    .factory()
    );

    public MeetingSummaryWorker(
            MeetingSummaryGenerationService service,
            MeetingSummaryGenerator generator
    ) {
        this.service = service;
        this.generator = generator;
    }

    @Scheduled(fixedDelayString = "${knot.meeting-summary.worker.poll-interval:PT5S}")
    public void poll() {
        if (!running.compareAndSet(
                false,
                true
        )) {
            return;
        }
        try {
            executor.execute(this::runInBackground);
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    public void runOnce() {
        try {
            service.registerEligible();
            for (Long id : service.findRunnable()) {
                claimAndProcess(id);
            }
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "MeetingSummaryWorker phase=poll",
                    exception
            );
        }
    }

    public void process(MeetingSummaryTask task) {
        try {
            service.succeed(
                    task,
                    generator.generate(task.transcript())
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Meeting summary failed recordingId=" + task.recordingId() + " attempt=" + task.attempt(),
                    exception
            );
            if (isInterrupted(exception)) {
                return;
            }
            recordFailure(
                    task,
                    exception
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void claimAndProcess(long id) {
        if (Thread.currentThread()
                .isInterrupted()) {
            return;
        }
        try {
            service.claim(id)
                    .ifPresent(this::process);
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "Meeting summary claim deferred recordingId=" + id,
                    exception
            );
        }
    }

    private void runInBackground() {
        try {
            runOnce();
        } finally {
            running.set(false);
        }
    }

    private void recordFailure(
            MeetingSummaryTask task,
            RuntimeException exception
    ) {
        try {
            service.fail(
                    task,
                    failureCode(exception),
                    isTransient(exception)
            );
        } catch (RuntimeException persistenceFailure) {
            ServerErrorLogger.record(
                    log,
                    "Meeting summary failure persistence deferred recordingId=" + task.recordingId(),
                    persistenceFailure
            );
        }
    }

    private String failureCode(RuntimeException exception) {
        if (ProjectException.class.isInstance(exception)) {
            return ProjectException.class.cast(exception)
                    .getErrorCode()
                    .getCode();
        }
        return "INTERNAL";
    }

    private boolean isTransient(RuntimeException exception) {
        if (!LlmException.class.isInstance(exception)) {
            return false;
        }
        ErrorCode code = LlmException.class.cast(exception)
                .getErrorCode();
        return code == LlmErrorCode.LLM_TIMEOUT || code == LlmErrorCode.LLM_RATE_LIMITED
                || code == LlmErrorCode.LLM_UNAVAILABLE;
    }

    private boolean isInterrupted(RuntimeException exception) {
        return Thread.currentThread()
                .isInterrupted() || failureCode(exception).equals("LLM_CALL_INTERRUPTED");
    }
}
