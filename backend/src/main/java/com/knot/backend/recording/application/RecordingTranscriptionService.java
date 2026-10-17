package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionExecution;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptionRetryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RemoteObservation;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RecordingTranscriptionService {
    private final RecordingTranscriptionStateService recordingTranscriptionStateService;
    private final TranscriptionProperties transcriptionProperties;
    private final TranscriptionClient transcriptionClient;
    private final RecordingAudioStorage recordingAudioStorage;
    private final Clock clock;
    private final UUID ownerToken = UUID.randomUUID();
    private final ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform()
                    .daemon()
                    .name("transcription-lease")
                    .factory()
    );
    private Thread transcriptionThread;
    private volatile boolean acceptingTranscriptionRequests = true;

    public RecordingTranscriptionService(
            RecordingTranscriptionStateService state,
            TranscriptionProperties properties,
            TranscriptionClient client,
            RecordingAudioStorage storage,
            Clock clock
    ) {
        this.recordingTranscriptionStateService = state;
        this.transcriptionProperties = properties;
        this.transcriptionClient = client;
        this.recordingAudioStorage = storage;
        this.clock = clock;
    }

    public RecordingTranscriptionRetryResult retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        if (!transcriptionProperties.enabled()) {
            throw new RecordingException(RecordingErrorCode.TRANSCRIPTION_DISABLED);
        }
        if (!acceptingTranscriptionRequests) {
            throw new RecordingException(RecordingErrorCode.AI_NOT_READY);
        }
        RecordingTranscriptionRetryResult result = resolveRetry(
                workspaceId,
                memberId,
                recordingId
        );
        dispatch();
        return result;
    }

    private RecordingTranscriptionRetryResult resolveRetry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        Optional<RecordingTranscriptionExecution> uncertain = recordingTranscriptionStateService.findUncertainRetry(
                workspaceId,
                memberId,
                recordingId
        );
        if (uncertain.isEmpty()) {
            return recordingTranscriptionStateService.retry(
                    workspaceId,
                    memberId,
                    recordingId
            );
        }
        RecordingTranscriptionExecution previous = uncertain.orElseThrow();
        Optional<AiTranscriptionJob> observed = findPreviousJob(previous);
        if (observed.filter(job -> job.status() == AiTranscriptionJob.Status.SUCCEEDED)
                .isPresent()) {
            return recordingTranscriptionStateService.adoptUncertainResult(
                    workspaceId,
                    memberId,
                    previous,
                    observed.orElseThrow()
                            .result()
            );
        }
        boolean resume = observed.filter(job -> job.status() == AiTranscriptionJob.Status.PROCESSING)
                .isPresent();
        return recordingTranscriptionStateService.resolveUncertainRetry(
                workspaceId,
                memberId,
                previous,
                resume
        );
    }

    private Optional<AiTranscriptionJob> findPreviousJob(RecordingTranscriptionExecution previous) {
        try {
            return Optional.of(transcriptionClient.get(previous.attemptId()));
        } catch (RecordingException exception) {
            if (exception.getErrorCode() != RecordingErrorCode.AI_JOB_NOT_FOUND) {
                throw exception;
            }
            return Optional.empty();
        }
    }

    public synchronized void dispatch() {
        if (!transcriptionProperties.enabled() || !acceptingTranscriptionRequests || transcriptionThread != null) {
            return;
        }
        recordingTranscriptionStateService.claimNext(
                ownerToken,
                transcriptionProperties.leaseDuration()
        )
                .ifPresent(execution -> {
                    transcriptionThread = Thread.ofPlatform()
                            .name("recording-transcription")
                            .unstarted(() -> execute(execution));
                    transcriptionThread.start();
                });
    }

    private void execute(RecordingTranscriptionExecution claimed) {
        ScheduledFuture<?> heartbeat = renewal.scheduleAtFixedRate(
                () -> renew(claimed),
                transcriptionProperties.leaseRenewInterval()
                        .toMillis(),
                transcriptionProperties.leaseRenewInterval()
                        .toMillis(),
                TimeUnit.MILLISECONDS
        );
        try {
            Optional<RecordingTranscriptionExecution> prepared = recordingTranscriptionStateService.prepare(claimed);
            if (prepared.isPresent()) {
                runRemote(prepared.orElseThrow());
            }
        } catch (RecordingException exception) {
            handleExecutionFailure(
                    claimed,
                    exception
            );
        } catch (RuntimeException exception) {
            logFailure(
                    claimed,
                    exception
            );
            // DB 반영이 실패하면 lease 만료 뒤 같은 attempt를 다시 조회한다.
        } finally {
            heartbeat.cancel(false);
            synchronized (this) {
                transcriptionThread = null;
            }
            if (acceptingTranscriptionRequests) {
                dispatch();
            }
        }
    }

    private void handleExecutionFailure(
            RecordingTranscriptionExecution execution,
            RecordingException exception
    ) {
        if (exception.getErrorCode() != RecordingErrorCode.AI_INTERRUPTED) {
            fail(
                    execution,
                    exception.getErrorCode()
                            .getCode()
            );
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            recordingTranscriptionStateService.requeue(
                    execution,
                    Duration.ZERO
            );
        } finally {
            restoreInterruption(interrupted);
        }
    }

    private void restoreInterruption(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    private void renew(RecordingTranscriptionExecution execution) {
        try {
            recordingTranscriptionStateService.renew(
                    execution,
                    transcriptionProperties.leaseDuration()
            );
        } catch (RuntimeException exception) {
            logFailure(
                    execution,
                    exception
            );
        }
    }

    private void runRemote(RecordingTranscriptionExecution execution) {
        restoreLegacyDeadline(execution);
        boolean submit = execution.remoteObservation() == RemoteObservation.NOT_SUBMITTED
                || execution.remoteObservation() == RemoteObservation.NOT_ACCEPTED;
        while (true) {
            checkInterruption();
            Optional<RecordingTranscriptionExecution> active = recordingTranscriptionStateService.prepare(execution);
            if (active.isEmpty()) {
                return;
            }
            RecordingTranscriptionExecution current = active.orElseThrow();
            if (deadlineExpired(current.executionDeadline())) {
                finishAtDeadline(current);
                return;
            }
            try {
                Optional<AiTranscriptionJob> observed = observeRemote(
                        current,
                        submit
                );
                if (observed.isEmpty()) {
                    return;
                }
                submit = false;
                if (adoptTerminal(
                        current,
                        observed.orElseThrow()
                )) {
                    return;
                }
                recordingTranscriptionStateService.observe(
                        current,
                        RemoteObservation.PROCESSING
                );
            } catch (RecordingException exception) {
                if (exception.getErrorCode() == RecordingErrorCode.AI_JOB_NOT_FOUND) {
                    recordingTranscriptionStateService.observe(
                            current,
                            RemoteObservation.NOT_FOUND
                    );
                    submit = true;
                    continue;
                }
                requireTransient(exception);
                submit = false;
            }
            pause();
        }
    }

    private void restoreLegacyDeadline(RecordingTranscriptionExecution execution) {
        if (execution.executionDeadline() == null && execution.remoteObservation() == RemoteObservation.UNCONFIRMED) {
            recordingTranscriptionStateService.restoreLegacyDeadline(
                    execution,
                    transcriptionProperties.jobTimeout()
            );
        }
    }

    private boolean deadlineExpired(Instant deadline) {
        return deadline != null && !clock.instant()
                .isBefore(deadline);
    }

    private Optional<AiTranscriptionJob> observeRemote(
            RecordingTranscriptionExecution execution,
            boolean submit
    ) {
        if (!submit) {
            return Optional.of(transcriptionClient.get(execution.attemptId()));
        }
        String url = recordingAudioStorage.presignDownload(
                execution.storageKey(),
                execution.storageVersionId(),
                execution.contentType(),
                execution.contentLength()
        );
        Optional<Instant> savedDeadline = recordingTranscriptionStateService.beginRemotePost(
                execution,
                transcriptionProperties.jobTimeout()
        );
        if (savedDeadline.isEmpty()) {
            return Optional.empty();
        }
        if (deadlineExpired(savedDeadline.orElseThrow())) {
            finishAtDeadline(execution);
            return Optional.empty();
        }
        try {
            return Optional.of(
                    transcriptionClient.submit(
                            execution.attemptId(),
                            url,
                            execution.speakerCount()
                    )
            );
        } catch (RecordingException exception) {
            if (exception.getErrorCode() != RecordingErrorCode.AI_BUSY
                    && exception.getErrorCode() != RecordingErrorCode.AI_NOT_READY) {
                throw exception;
            }
            recordingTranscriptionStateService.deferReadiness(
                    execution,
                    transcriptionProperties.readinessTimeout(),
                    execution.executionDeadline() == null
            );
            return Optional.empty();
        }
    }

    private boolean adoptTerminal(
            RecordingTranscriptionExecution execution,
            AiTranscriptionJob job
    ) {
        if (job.status() == AiTranscriptionJob.Status.SUCCEEDED) {
            recordingTranscriptionStateService.observe(
                    execution,
                    RemoteObservation.SUCCEEDED
            );
            recordingTranscriptionStateService.complete(
                    execution,
                    job.result()
            );
            return true;
        }
        if (job.status() == AiTranscriptionJob.Status.FAILED) {
            recordingTranscriptionStateService.observe(
                    execution,
                    RemoteObservation.FAILED
            );
            fail(
                    execution,
                    job.failureCode()
            );
            return true;
        }
        return false;
    }

    private void finishAtDeadline(RecordingTranscriptionExecution execution) {
        try {
            AiTranscriptionJob finalObservation = transcriptionClient.get(execution.attemptId());
            if (adoptTerminal(
                    execution,
                    finalObservation
            )) {
                return;
            }
            recordingTranscriptionStateService.observe(
                    execution,
                    RemoteObservation.PROCESSING
            );
        } catch (RecordingException exception) {
            if (exception.getErrorCode() == RecordingErrorCode.AI_INTERRUPTED) {
                throw exception;
            }
        }
        fail(
                execution,
                "AI_OUTCOME_UNKNOWN"
        );
    }

    private void requireTransient(RecordingException exception) {
        if (exception.getErrorCode() != RecordingErrorCode.AI_TIMEOUT
                && exception.getErrorCode() != RecordingErrorCode.AI_UNAVAILABLE
                && exception.getErrorCode() != RecordingErrorCode.AI_NOT_READY) {
            throw exception;
        }
    }

    private void pause() {
        try {
            Thread.sleep(transcriptionProperties.pollInterval());
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new RecordingException(
                    RecordingErrorCode.AI_INTERRUPTED,
                    exception
            );
        }
    }

    private void checkInterruption() {
        if (Thread.currentThread()
                .isInterrupted()) {
            throw new RecordingException(RecordingErrorCode.AI_INTERRUPTED);
        }
    }

    private void fail(
            RecordingTranscriptionExecution execution,
            String code
    ) {
        recordingTranscriptionStateService.fail(
                execution,
                code
        );
    }

    private void logFailure(
            RecordingTranscriptionExecution execution,
            RuntimeException exception
    ) {
        ServerErrorLogger.recordTaskFailure(
                log,
                "Transcription failure recordingId=" + execution.recordingId() + " attemptId=" + execution.attemptId(),
                exception
        );
    }

    private void interrupt(Thread running) {
        if (running != null) {
            running.interrupt();
        }
    }

    @PreDestroy
    public void close() {
        Thread running;
        synchronized (this) {
            acceptingTranscriptionRequests = false;
            running = transcriptionThread;
            interrupt(running);
        }
        if (running != null) {
            try {
                running.join(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
        renewal.shutdownNow();
    }
}
