package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.AudioFinalizationClaim;
import com.knot.backend.recording.application.dto.result.AudioFinalizationInput;
import com.knot.backend.recording.domain.AudioFinalizationLeaseLostException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.model.RecordingAudioStream;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.model.RecordingAudioChunk;
import com.knot.backend.recording.domain.model.RecordingAudioChunkStatus;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.StoredAudioChunk;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import com.knot.backend.recording.domain.model.RecordingAudioMode;
import com.knot.backend.recording.domain.model.RecordingAudioSealKind;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioChunkRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioUploadRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RecordingAudioFinalizationTransaction {
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final RecordingSessionRepository sessions;
    private final RecordingAudioStreamRepository streams;
    private final RecordingAudioChunkRepository chunks;
    private final RecordingAudioFinalizationRepository jobs;
    private final RecordingAudioUploadRepository uploads;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final RecordingAccess access;
    private final RecordingTranscriptionStateService transcriptions;
    private final RecordingTranscriptionService dispatcher;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    public void register(long recordingId) {
        RecordingSession session = lockSession(recordingId);
        RecordingAudioStream stream = lockStream(recordingId);
        if (!available(session) || !stream.isSealed() || stream.isFrozen()) {
            return;
        }
        if (jobs.findByRecordingIdForUpdate(recordingId)
                .isEmpty()) {
            jobs.save(
                    RecordingAudioFinalization.create(
                            recordingId,
                            clock.instant()
                    )
            );
        }
    }

    @Transactional
    public Optional<AudioFinalizationClaim> claim(long recordingId) {
        RecordingSession session = lockSession(recordingId);
        lockStream(recordingId);
        RecordingAudioFinalization job = job(recordingId);
        if (!available(session)) {
            job.discard(clock.instant());
            return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        if (!job.claim(
                owner,
                clock.instant(),
                LEASE
        )) {
            return Optional.empty();
        }
        return Optional.of(
                new AudioFinalizationClaim(
                        recordingId,
                        owner,
                        job.getClaimEpoch()
                )
        );
    }

    @Transactional(readOnly = true)
    public List<RecordingAudioChunk> findChunks(AudioFinalizationClaim claim) {
        return chunks.findAllByRecordingIdOrderBySequence(claim.recordingId());
    }

    @Transactional
    public Optional<AudioFinalizationInput> reconcile(
            AudioFinalizationClaim claim,
            Map<Integer, StoredAudioChunk> observations,
            java.time.Instant observationStartedAt
    ) {
        RecordingSession session = lockSession(claim.recordingId());
        RecordingAudioStream stream = lockStream(claim.recordingId());
        RecordingAudioFinalization job = owned(
                claim,
                session
        );
        if (job.getManifestJson() != null) {
            return Optional.of(
                    input(
                            job,
                            stream
                    )
            );
        }
        stream.validateNotFrozen();
        List<RecordingAudioChunk> all = chunks.findAllByRecordingIdOrderBySequence(claim.recordingId());
        for (RecordingAudioChunk chunk : all) {
            reconcileChunk(
                    chunk,
                    observations.get(chunk.getSequence())
            );
        }
        boolean missing = all.stream()
                .anyMatch(chunk -> chunk.getStatus() != RecordingAudioChunkStatus.VERIFIED);
        if (mustWaitForObjects(
                stream,
                missing,
                observationStartedAt
        )) {
            job.waitForObjects(
                    claim.ownerToken(),
                    claim.claimEpoch(),
                    clock.instant(),
                    clock.instant()
                            .plusSeconds(1)
            );
            return Optional.empty();
        }
        List<AudioManifestPart> manifest = all.stream()
                .map(this::manifestPart)
                .toList();
        String json = mapper.writeValueAsString(manifest);
        String hash = hash(json);
        String quality = quality(
                missing,
                stream.isTailUnknown()
        );
        stream.freeze();
        job.freeze(
                claim.ownerToken(),
                claim.claimEpoch(),
                clock.instant(),
                json,
                hash,
                quality
        );
        if (manifest.stream()
                .noneMatch(part -> !part.isMissing())) {
            job.fail(
                    claim.ownerToken(),
                    claim.claimEpoch(),
                    clock.instant(),
                    "AUDIO_NO_CONTENT",
                    false
            );
            return Optional.empty();
        }
        return Optional.of(
                input(
                        job,
                        stream
                )
        );
    }

    @Transactional
    public void renew(AudioFinalizationClaim claim) {
        RecordingSession session = lockSession(claim.recordingId());
        lockStream(claim.recordingId());
        owned(
                claim,
                session
        ).renew(
                claim.ownerToken(),
                claim.claimEpoch(),
                clock.instant(),
                LEASE
        );
    }

    @Transactional
    public void adopt(
            AudioFinalizationClaim claim,
            StoredAudioChunk stored
    ) {
        RecordingSession session = lockSession(claim.recordingId());
        RecordingAudioStream stream = lockStream(claim.recordingId());
        RecordingAudioFinalization job = owned(
                claim,
                session
        );
        if (stored.byteLength() != stream.getTotalFrameCount() * 2 + 44) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_MISMATCH);
        }
        if (uploads.findByRecordingId(claim.recordingId())
                .isPresent()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_UPLOAD_ALREADY_COMPLETED);
        }
        uploads.save(
                RecordingAudioUpload.adoptServerWav(
                        session,
                        job.getFinalKey(),
                        stored,
                        clock.instant()
                )
        );
        job.ready(
                claim.ownerToken(),
                claim.claimEpoch(),
                clock.instant(),
                stored
        );
        transcriptions.registerAutomatic(claim.recordingId());
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatcher.dispatch();
                    }
                });
    }

    @Transactional
    public void fail(
            AudioFinalizationClaim claim,
            String code,
            boolean retryable
    ) {
        RecordingSession session = lockSession(claim.recordingId());
        lockStream(claim.recordingId());
        owned(
                claim,
                session
        ).fail(
                claim.ownerToken(),
                claim.claimEpoch(),
                clock.instant(),
                code,
                retryable
        );
    }

    @Transactional
    public boolean retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        RecordingSession session = access.loadOwnedForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        lockStream(recordingId);
        if (!available(session)) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SAVE_RETRY_NOT_ALLOWED);
        }
        return job(recordingId).retry(clock.instant());
    }

    private AudioFinalizationInput input(
            RecordingAudioFinalization job,
            RecordingAudioStream stream
    ) {
        return new AudioFinalizationInput(
                job.getFinalKey(),
                stream.getTotalFrameCount(),
                Arrays.asList(
                        mapper.readValue(
                                job.getManifestJson(),
                                AudioManifestPart[].class
                        )
                )
        );
    }

    private RecordingAudioFinalization owned(
            AudioFinalizationClaim claim,
            RecordingSession session
    ) {
        RecordingAudioFinalization job = job(claim.recordingId());
        job.validateOwner(
                claim.ownerToken(),
                claim.claimEpoch(),
                clock.instant()
        );
        if (!available(session)) {
            throw new AudioFinalizationLeaseLostException();
        }
        return job;
    }

    private RecordingAudioFinalization job(long recordingId) {
        return jobs.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private RecordingSession lockSession(long recordingId) {
        long workspaceId = sessions.findWorkspaceIdById(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
        workspaces.findIncludingDeletedByIdForUpdate(workspaceId);
        return sessions.findByIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private RecordingAudioStream lockStream(long recordingId) {
        return streams.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    private boolean available(RecordingSession session) {
        return session.getAudioMode() == RecordingAudioMode.PCM16_CHUNKS_V1
                && session.getStatus() == RecordingStatus.ENDED && session.getEndedAt() != null && clock.instant()
                        .isBefore(
                                session.getEndedAt()
                                        .plus(Duration.ofDays(30))
                        )
                && workspaces.findById(session.getWorkspaceId())
                        .isPresent()
                && members.existsByWorkspaceIdAndMemberId(
                        session.getWorkspaceId(),
                        session.getMemberId()
                );
    }

    @Transactional
    public Optional<AudioFinalizationInput> frozenInput(AudioFinalizationClaim claim) {
        RecordingSession session = lockSession(claim.recordingId());
        RecordingAudioStream stream = lockStream(claim.recordingId());
        RecordingAudioFinalization job = owned(
                claim,
                session
        );
        if (job.getManifestJson() == null) {
            return Optional.empty();
        }
        return Optional.of(
                input(
                        job,
                        stream
                )
        );
    }

    private boolean mustWaitForObjects(
            RecordingAudioStream stream,
            boolean missing,
            java.time.Instant observedAt
    ) {
        if (stream.getSealKind() != RecordingAudioSealKind.NORMAL && clock.instant()
                .isBefore(stream.getAcceptUntil())) {
            return true;
        }
        return missing && (clock.instant()
                .isBefore(stream.getAcceptUntil()) || observedAt == null
                || observedAt.isBefore(stream.getAcceptUntil()));
    }

    private void reconcileChunk(
            RecordingAudioChunk chunk,
            StoredAudioChunk observation
    ) {
        if (chunk.getStatus() == RecordingAudioChunkStatus.VERIFIED || observation == null || !observation.exists()) {
            return;
        }
        if (observation.checksumSha256() == null || observation.versionId() == null) {
            throw new RecordingException(RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE);
        }
        chunk.verify(
                observation,
                clock.instant()
        );
    }

    private AudioManifestPart manifestPart(RecordingAudioChunk chunk) {
        String version = null;
        if (chunk.getStatus() == RecordingAudioChunkStatus.VERIFIED) {
            version = chunk.getStorageVersionId();
        }
        return new AudioManifestPart(
                chunk.getSequence(),
                chunk.getSampleOffset(),
                chunk.getFrameCount(),
                chunk.getStorageKey(),
                chunk.getChecksumSha256(),
                version
        );
    }

    private String quality(
            boolean missing,
            boolean tailUnknown
    ) {
        if (missing || tailUnknown) {
            return "PARTIAL";
        }
        return "COMPLETE";
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8))
                    );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new RecordingException(
                    RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                    exception
            );
        }
    }
}
