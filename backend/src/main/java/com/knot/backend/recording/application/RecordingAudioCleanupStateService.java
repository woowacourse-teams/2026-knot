package com.knot.backend.recording.application;

import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.recording.domain.model.RecordingAudioCleanup;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupPolicy;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupScope;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
public class RecordingAudioCleanupStateService {
    private final RecordingAudioCleanupRepository cleanup;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingSessionRepository sessions;
    private final WorkspaceRepository workspaces;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioFinalizationRepository finalizations;
    private final RetentionCleanupProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void afterDeparture(
            long workspaceId,
            long memberId
    ) {
        for (Long recordingId : cleanup.findOwned(
                workspaceId,
                memberId
        )) {
            cleanup.track(
                    recordingId,
                    now()
            );
            schedule(recordingId);
        }
    }

    @Transactional
    public void schedule(long recordingId) {
        Optional<Long> workspaceId = sessions.findWorkspaceIdById(recordingId);
        workspaceId.ifPresent(workspaces::findByIdForUpdate);
        sessions.findByIdForUpdate(recordingId);
        streams.findByRecordingIdForUpdate(recordingId);
        finalizations.findByRecordingIdForUpdate(recordingId);
        Optional<RecordingAudioUpload> upload = uploads.findByRecordingIdForUpdate(recordingId);
        Optional<RecordingAudioCleanup> found = cleanup.findForUpdate(recordingId);
        if (found.isEmpty()) {
            return;
        }
        Instant now = now();
        RecordingAudioCleanupPolicy policy = cleanup.policy(
                recordingId,
                now
        );
        if (policy.scope() == RecordingAudioCleanupScope.DORMANT) {
            return;
        }
        if (policy.scope() == RecordingAudioCleanupScope.ALL) {
            upload.ifPresent(value -> value.revoke(now));
            streams.findByRecordingIdForUpdate(recordingId)
                    .ifPresent(stream -> stream.freeze());
            finalizations.findByRecordingIdForUpdate(recordingId)
                    .ifPresent(
                            job -> closePendingFinalization(
                                    job,
                                    now
                            )
                    );
        }
        found.orElseThrow()
                .request(
                        policy.scope(),
                        policy.maxUrlExpiresAt(),
                        now
                );
    }

    @Transactional
    public Optional<CleanupExecution> claim(long recordingId) {
        Optional<RecordingAudioCleanup> found = cleanup.findForUpdate(recordingId);
        if (found.isEmpty() || !found.orElseThrow()
                .claim(
                        now(),
                        properties.getExecutionLease()
                )) {
            return Optional.empty();
        }
        RecordingAudioCleanup task = found.orElseThrow();
        return Optional.of(
                new CleanupExecution(
                        task.getRecordingId(),
                        task.getClaimEpoch(),
                        task.deletionPrefix()
                )
        );
    }

    @Transactional
    public void finish(
            CleanupExecution execution,
            boolean success
    ) {
        cleanup.findForUpdate(execution.recordingId())
                .ifPresent(
                        task -> task.finish(
                                execution.epoch(),
                                success,
                                now()
                        )
                );
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }

    public record CleanupExecution(
            long recordingId,
            long epoch,
            String storagePrefix
    ) {
    }
    private void closePendingFinalization(
            RecordingAudioFinalization job,
            Instant now
    ) {
        if (job.getStatus() == AudioFinalizationStatus.READY) {
            return;
        }
        job.discard(now);
    }
}
