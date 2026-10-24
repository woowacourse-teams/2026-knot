package com.knot.backend.recording.application;

import com.knot.backend.recording.domain.model.RecordingAudioUpload;

import com.knot.backend.recording.domain.model.RecordingTranscriptionInput;
import com.knot.backend.recording.domain.model.RecordingAudioCleanupScope;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionInputRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingInputPreparationState {
    private final RecordingTranscriptionInputRepository inputs;
    private final RecordingSessionRepository sessions;
    private final RecordingAudioUploadRepository uploads;
    private final RecordingAudioCleanupRepository cleanup;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final RecordingTranscriptionStateService transcriptions;
    private final Clock clock;

    @Transactional
    public Optional<Claim> claim(
            long recordingId,
            Duration lease
    ) {
        inputs.lockAdmission();
        if (!available(recordingId)) {
            inputs.cancelByRecordingId(recordingId);
            return Optional.empty();
        }
        RecordingTranscriptionInput input = inputs.findByRecordingIdForUpdate(recordingId)
                .orElseThrow();
        Optional<RecordingTranscriptionInput> busy = inputs.findProcessing();
        if (busy.isPresent() && busy.orElseThrow()
                .getRecordingId() != recordingId) {
            return Optional.empty();
        }
        if (input.expire(now())) {
            transcriptions.failIncremental(
                    recordingId,
                    "INPUT_PREPARATION_TIMEOUT"
            );
            return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        if (!input.claim(
                owner,
                now(),
                lease
        )) {
            return Optional.empty();
        }
        return Optional.of(
                new Claim(
                        recordingId,
                        input.getAttemptId(),
                        owner,
                        input.getClaimEpoch(),
                        input.getSourceStorageKey(),
                        input.getSourceVersionId(),
                        input.getSourceContentType(),
                        input.getSourceContentLength()
                )
        );
    }

    @Transactional
    public boolean renew(
            Claim claim,
            Duration lease
    ) {
        Optional<RecordingTranscriptionInput> input = owned(claim);
        return input.isPresent() && input.orElseThrow()
                .renew(
                        claim.attemptId(),
                        claim.ownerToken(),
                        claim.epoch(),
                        now(),
                        lease
                );
    }

    @Transactional
    public boolean ready(
            Claim claim,
            String key,
            StoredAudioChunk stored,
            long frames
    ) {
        Optional<RecordingTranscriptionInput> input = owned(claim);
        if (input.isEmpty()) {
            return false;
        }
        input.orElseThrow()
                .ready(
                        key,
                        stored,
                        frames
                );
        return true;
    }

    @Transactional
    public void fail(
            Claim claim,
            String code
    ) {
        owned(claim).ifPresent(input -> {
            input.fail(code);
            transcriptions.failIncremental(
                    claim.recordingId(),
                    code
            );
        });
    }

    private Optional<RecordingTranscriptionInput> owned(Claim claim) {
        if (!available(claim.recordingId())) {
            inputs.cancelByRecordingId(claim.recordingId());
            return Optional.empty();
        }
        return inputs.findByRecordingIdForUpdate(claim.recordingId())
                .filter(
                        input -> input.owned(
                                claim.attemptId(),
                                claim.ownerToken(),
                                claim.epoch(),
                                now()
                        )
                );
    }

    private boolean available(long id) {
        Optional<Long> workspace = sessions.findWorkspaceIdById(id);
        if (workspace.isEmpty() || workspaces.findByIdForUpdate(workspace.orElseThrow())
                .isEmpty()) {
            return false;
        }
        return sessions.findByIdForUpdate(id)
                .filter(session -> session.isTranscribableIn(workspace.orElseThrow()))
                .filter(
                        session -> members.existsByWorkspaceIdAndMemberId(
                                workspace.orElseThrow(),
                                session.getMemberId()
                        )
                )
                .filter(session -> session.getEndedAt() != null)
                .isPresent()
                && uploads.findByRecordingIdForUpdate(id)
                        .filter(RecordingAudioUpload::isAudioAvailable)
                        .isPresent()
                && cleanup.findForUpdate(id)
                        .map(task -> task.getScope() != RecordingAudioCleanupScope.ALL)
                        .orElse(true);
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public record Claim(
            long recordingId,
            UUID attemptId,
            UUID ownerToken,
            long epoch,
            String sourceStorageKey,
            String sourceVersionId,
            String sourceContentType,
            long sourceContentLength
    ) {
    }
}
