package com.knot.backend.recording.application;

import com.knot.backend.global.config.TranscriptionProperties;
import com.knot.backend.recording.application.dto.command.AiWindowInput;
import com.knot.backend.recording.application.dto.result.AiTranscriptionJob;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowAudioSource;
import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.repository.IncrementalTranscriptionQueue;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class IncrementalTranscriptionWorker {
    private static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(5);

    private final RecordingTranscriptionWindowService state;
    private final IncrementalTranscriptionCoordinator coordinator;
    private final IncrementalTranscriptionQueue queue;
    private final TranscriptionWindowAudioAssembler assembler;
    private final TranscriptionClient client;
    private final RecordingAudioStorage storage;
    private final TranscriptionProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform()
                    .daemon()
                    .name("incremental-lease")
                    .factory()
    );
    private volatile boolean accepting = true;
    private Thread worker;
    private long scanCursor;

    public IncrementalTranscriptionWorker(
            RecordingTranscriptionWindowService state,
            IncrementalTranscriptionCoordinator coordinator,
            IncrementalTranscriptionQueue queue,
            TranscriptionWindowAudioAssembler assembler,
            TranscriptionClient client,
            RecordingAudioStorage storage,
            TranscriptionProperties properties,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.state = state;
        this.coordinator = coordinator;
        this.queue = queue;
        this.assembler = assembler;
        this.client = client;
        this.storage = storage;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${knot.transcription.incremental-scan-interval:PT1S}")
    public void tick() {
        if (!accepting || !properties.enabled()) {
            return;
        }
        List<Long> recordings = queue.findRecordings(
                scanCursor,
                100
        );
        for (long recordingId : recordings) {
            try {
                coordinator.advance(recordingId);
            } catch (RuntimeException exception) {
                log.warn(
                        "구간 전사 계획 실패 recordingId={} type={}",
                        recordingId,
                        exception.getClass()
                                .getSimpleName()
                );
            }
            scanCursor = recordingId;
        }
        if (recordings.size() < 100) {
            scanCursor = 0;
        }
        dispatch();
    }

    public synchronized void dispatch() {
        if (!accepting || !properties.enabled() || worker != null) {
            return;
        }
        for (IncrementalTranscriptionQueue.WindowTask task : queue.findCandidates(
                clock.instant(),
                20
        )) {
            try {
                if (state.expire(
                        task.recordingId(),
                        task.windowId()
                )) {
                    continue;
                }
                Optional<TranscriptionWindowClaim> claimed = state.claim(
                        task.recordingId(),
                        task.windowId(),
                        properties.leaseDuration(),
                        EXECUTION_TIMEOUT
                );
                if (claimed.isEmpty()) {
                    continue;
                }
                worker = Thread.ofPlatform()
                        .name("incremental-transcription")
                        .unstarted(() -> execute(claimed.orElseThrow()));
                worker.start();
                return;
            } catch (RuntimeException exception) {
                log.warn(
                        "구간 전사 선점 실패 windowId={} type={}",
                        task.windowId(),
                        exception.getClass()
                                .getSimpleName()
                );
                return;
            }
        }
    }

    private void execute(TranscriptionWindowClaim claim) {
        AtomicBoolean owned = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = renewal.scheduleAtFixedRate(
                () -> renewOwnership(
                        claim,
                        owned
                ),
                properties.leaseRenewInterval()
                        .toMillis(),
                properties.leaseRenewInterval()
                        .toMillis(),
                TimeUnit.MILLISECONDS
        );
        try {
            runRemote(
                    claim,
                    owned
            );
        } catch (RuntimeException exception) {
            // 원격 성공 여부나 DB 채택이 불확실하면 슬롯을 보존하고 같은 attempt로 다시 조회한다.
            log.warn(
                    "구간 전사 실행 중단 windowId={} type={}",
                    claim.windowId(),
                    exception.getClass()
                            .getSimpleName()
            );
        } finally {
            heartbeat.cancel(false);
            synchronized (this) {
                worker = null;
            }
            if (accepting) {
                try {
                    coordinator.advance(claim.recordingId());
                } catch (RuntimeException exception) {
                    log.warn(
                            "구간 전사 완료 검사 실패 recordingId={} type={}",
                            claim.recordingId(),
                            exception.getClass()
                                    .getSimpleName()
                    );
                }
                dispatch();
            }
        }
    }

    private void renewOwnership(
            TranscriptionWindowClaim claim,
            AtomicBoolean owned
    ) {
        if (!owned.get()) {
            return;
        }
        try {
            if (!state.renew(
                    claim,
                    properties.leaseDuration()
            )) {
                owned.set(false);
            }
        } catch (RuntimeException exception) {
            owned.set(false);
        }
    }

    private void runRemote(
            TranscriptionWindowClaim claim,
            AtomicBoolean owned
    ) {
        while (accepting && owned.get() && !Thread.currentThread()
                .isInterrupted()) {
            if (!state.renew(
                    claim,
                    properties.leaseDuration()
            )) {
                state.expire(
                        claim.recordingId(),
                        claim.windowId()
                );
                return;
            }
            try {
                AiTranscriptionJob job;
                try {
                    job = client.get(claim.attemptId());
                } catch (RecordingException missing) {
                    if (missing.getErrorCode() != RecordingErrorCode.AI_JOB_NOT_FOUND) {
                        throw missing;
                    }
                    Optional<TranscriptionWindowAudioSource> source = state.source(claim);
                    if (source.isEmpty()) {
                        return;
                    }
                    TranscriptionWindowAudioAssembler.MaterializedWindowAudio audio = assembler.materialize(
                            source.orElseThrow(),
                            () -> checkpoint(
                                    claim,
                                    owned
                            )
                    );
                    checkpoint(
                            claim,
                            owned
                    );
                    String url = storage.presignDownload(
                            audio.storageKey(),
                            audio.metadata()
                                    .versionId(),
                            "audio/wav",
                            audio.metadata()
                                    .byteLength()
                    );
                    tools.jackson.databind.JsonNode manifest = mapper.readTree(claim.manifestJson());
                    try {
                        job = client.submitWindow(
                                claim.attemptId(),
                                url,
                                new AiWindowInput(
                                        source.orElseThrow()
                                                .pipelineVersion(),
                                        claim.manifestSha256(),
                                        manifest.path("inputStartFrame")
                                                .asLong(),
                                        manifest.path("inputEndFrame")
                                                .asLong(),
                                        claim.decodePolicy()
                                )
                        );
                    } catch (RecordingException rejected) {
                        if (rejected.getErrorCode() != RecordingErrorCode.AI_BUSY
                                && rejected.getErrorCode() != RecordingErrorCode.AI_NOT_READY) {
                            throw rejected;
                        }
                        checkpoint(
                                claim,
                                owned
                        );
                        state.defer(
                                claim,
                                clock.instant()
                                        .plusSeconds(2),
                                rejected.getErrorCode()
                                        .getCode()
                        );
                        return;
                    }
                }
                checkpoint(
                        claim,
                        owned
                );
                if (job.status() == AiTranscriptionJob.Status.SUCCEEDED) {
                    AiWindowEvidence result = job.result() == null
                            ? null
                            : job.result()
                                    .window();
                    validateEvidence(
                            claim,
                            result
                    );
                    state.succeed(
                            claim,
                            1,
                            mapper.writeValueAsString(result)
                    );
                    return;
                }
                if (job.status() == AiTranscriptionJob.Status.FAILED) {
                    state.fail(
                            claim,
                            job.failureCode()
                    );
                    return;
                }
            } catch (RecordingException exception) {
                if (exception.getErrorCode() == RecordingErrorCode.AI_INTERRUPTED) {
                    return;
                }
                if (!isTransient(exception)) {
                    state.fail(
                            claim,
                            exception.getErrorCode()
                                    .getCode()
                    );
                    return;
                }
            }
            pause();
        }
    }

    private void validateEvidence(
            TranscriptionWindowClaim claim,
            AiWindowEvidence evidence
    ) {
        tools.jackson.databind.JsonNode manifest = mapper.readTree(claim.manifestJson());
        Optional<TranscriptionWindowAudioSource> source = state.source(claim);
        if (source.isEmpty()) {
            throw new RecordingException(RecordingErrorCode.AI_INTERRUPTED);
        }
        if (evidence == null || !source.orElseThrow()
                .pipelineVersion()
                .equals(evidence.pipelineVersion()) || !claim.manifestSha256()
                        .equals(evidence.inputManifestSha256())
                || evidence.inputStartFrame() != manifest.path("inputStartFrame")
                        .asLong()
                || evidence.inputEndFrame() != manifest.path("inputEndFrame")
                        .asLong()) {
            throw new RecordingException(RecordingErrorCode.AI_INVALID_RESPONSE);
        }
    }

    private boolean isTransient(RecordingException exception) {
        return exception.getErrorCode() == RecordingErrorCode.AI_BUSY
                || exception.getErrorCode() == RecordingErrorCode.AI_NOT_READY
                || exception.getErrorCode() == RecordingErrorCode.AI_TIMEOUT
                || exception.getErrorCode() == RecordingErrorCode.AI_UNAVAILABLE
                || exception.getErrorCode() == RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE;
    }

    private void checkpoint(
            TranscriptionWindowClaim claim,
            AtomicBoolean owned
    ) {
        if (!accepting || !owned.get() || Thread.currentThread()
                .isInterrupted()
                || !state.renew(
                        claim,
                        properties.leaseDuration()
                )) {
            throw new RecordingException(RecordingErrorCode.AI_INTERRUPTED);
        }
    }

    private void pause() {
        try {
            Thread.sleep(properties.pollInterval());
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
        }
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
                running.join(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
        renewal.shutdownNow();
    }
}
