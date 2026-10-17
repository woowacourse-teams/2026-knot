package com.knot.ai.transcription.application;

import com.knot.ai.transcription.application.command.CreateTranscriptionCommand;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionJobService {
    private static final long SHUTDOWN_WAIT_MILLIS = 15_000;

    private final TranscriptionEngine engine;
    private JobSnapshot current;
    private Thread worker;
    private boolean accepting = true;

    public TranscriptionJobService(TranscriptionEngine engine) {
        this.engine = engine;
    }

    public synchronized JobSnapshot create(CreateTranscriptionCommand request) {
        long started = TranscriptionLog.start();
        if (!accepting) {
            throw rejection(
                    request.attemptId(),
                    started,
                    TranscriptionErrorCode.NOT_READY
            );
        }
        if (current != null && current.attemptId()
                .equals(request.attemptId())) {
            TranscriptionLog.record(
                    "recording.transcription.accept",
                    "replayed",
                    request.attemptId(),
                    started,
                    null,
                    null
            );
            return current;
        }
        if (worker != null) {
            throw rejection(
                    request.attemptId(),
                    started,
                    TranscriptionErrorCode.BUSY
            );
        }
        if (!engine.isReady()) {
            throw rejection(
                    request.attemptId(),
                    started,
                    TranscriptionErrorCode.NOT_READY
            );
        }
        current = new JobSnapshot(
                request.attemptId(),
                JobSnapshot.Status.PROCESSING,
                null,
                null
        );
        worker = Thread.ofPlatform()
                .name("transcription-job")
                .unstarted(() -> execute(request));
        try {
            worker.start();
            TranscriptionLog.record(
                    "recording.transcription.accept",
                    "accepted",
                    request.attemptId(),
                    started,
                    null,
                    null
            );
        } catch (RuntimeException exception) {
            TranscriptionLog.record(
                    "recording.transcription.accept",
                    "failed",
                    request.attemptId(),
                    started,
                    TranscriptionErrorCode.EXECUTOR_REJECTED.getCode(),
                    exception
            );
            current = new JobSnapshot(
                    request.attemptId(),
                    JobSnapshot.Status.FAILED,
                    TranscriptionErrorCode.EXECUTOR_REJECTED.getCode(),
                    null
            );
            worker = null;
        }
        return current;
    }

    public synchronized JobSnapshot get(UUID attemptId) {
        if (current == null || !current.attemptId()
                .equals(attemptId)) {
            throw new TranscriptionException(TranscriptionErrorCode.JOB_NOT_FOUND);
        }
        return current;
    }

    public synchronized boolean isReady() {
        return accepting && engine.isReady();
    }

    @PreDestroy
    public void close() {
        Thread running;
        synchronized (this) {
            accepting = false;
            running = worker;
            if (running != null) {
                running.interrupt();
            }
        }
        if (running != null) {
            try {
                running.join(SHUTDOWN_WAIT_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
    }

    private TranscriptionException rejection(
            UUID attemptId,
            long started,
            TranscriptionErrorCode code
    ) {
        TranscriptionLog.record(
                "recording.transcription.accept",
                "rejected",
                attemptId,
                started,
                code.getCode(),
                null
        );
        return new TranscriptionException(code);
    }

    private void execute(CreateTranscriptionCommand request) {
        long started = TranscriptionLog.start();
        TranscriptionLog.record(
                "recording.transcription.execute",
                "started",
                request.attemptId(),
                started,
                null,
                null
        );
        Exception failure = null;
        JobSnapshot outcome = new JobSnapshot(
                request.attemptId(),
                JobSnapshot.Status.FAILED,
                TranscriptionErrorCode.INFERENCE_FAILED.getCode(),
                null
        );
        try {
            TranscriptResult result = Objects.requireNonNull(engine.transcribe(request));
            outcome = new JobSnapshot(
                    request.attemptId(),
                    JobSnapshot.Status.SUCCEEDED,
                    null,
                    result
            );
        } catch (Exception exception) {
            if (exception instanceof TranscriptionException knownFailure) {
                outcome = new JobSnapshot(
                        request.attemptId(),
                        JobSnapshot.Status.FAILED,
                        knownFailure.getCode(),
                        null
                );
            }
            failure = exception;
        } finally {
            Thread.interrupted();
            try {
                TranscriptionLog.stage(
                        "recording.transcription.cleanup",
                        request.attemptId(),
                        TranscriptionErrorCode.PROCESS_CLEANUP_FAILED,
                        () -> {
                            engine.cleanup(request.attemptId());
                            return null;
                        }
                );
            } catch (Exception exception) {
                synchronized (this) {
                    accepting = false;
                }
            }
            synchronized (this) {
                current = outcome;
                worker = null;
                TranscriptionLog.record(
                        "recording.transcription.execute",
                        outcome.status() == JobSnapshot.Status.SUCCEEDED ? "completed" : "failed",
                        request.attemptId(),
                        started,
                        outcome.failureCode(),
                        failure
                );
            }
        }
    }
}
