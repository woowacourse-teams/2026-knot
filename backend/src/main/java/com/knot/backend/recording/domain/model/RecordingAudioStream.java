package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.AudioSequenceGapException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.time.Duration;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "recording_audio_streams")
public class RecordingAudioStream {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_id", nullable = false, updatable = false)
    private long recordingId;

    @Column(name = "next_sequence", nullable = false)
    private int nextSequence;

    @Column(name = "total_frame_count", nullable = false)
    private long totalFrameCount;

    @Column(nullable = false)
    private boolean frozen;

    @Enumerated(EnumType.STRING)
    @Column(name = "seal_kind", length = 20)
    private RecordingAudioSealKind sealKind;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @Column(name = "accept_until")
    private Instant acceptUntil;

    @Column(name = "tail_unknown", nullable = false)
    private boolean tailUnknown;

    protected RecordingAudioStream() {}

    private RecordingAudioStream(long recordingId) {
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        this.recordingId = recordingId;
        this.nextSequence = 1;
    }

    public static RecordingAudioStream create(long recordingId) {
        return new RecordingAudioStream(recordingId);
    }

    public void reserve(AudioChunkDescriptor descriptor) {
        validateNotFrozen();
        if (isSealed()) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SEAL_CONFLICT);
        }
        if (descriptor == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_CHUNK);
        }
        if (descriptor.sequence() > nextSequence) {
            throw new AudioSequenceGapException(nextSequence);
        }
        if (descriptor.sequence() != nextSequence || descriptor.sampleOffset() != totalFrameCount) {
            throw new RecordingException(RecordingErrorCode.AUDIO_CHUNK_CONFLICT);
        }
        totalFrameCount += descriptor.frameCount();
        nextSequence++;
    }

    public boolean isSealed() {
        return sealedAt != null;
    }

    public int getLastSequence() {
        return nextSequence - 1;
    }

    public void seal(
            RecordingAudioSealKind kind,
            Instant endedAt
    ) {
        if (isSealed()) {
            return;
        }
        if (kind == null || endedAt == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_AUDIO_SEAL);
        }
        sealKind = kind;
        sealedAt = endedAt;
        acceptUntil = endedAt.plus(Duration.ofSeconds(120));
        tailUnknown = kind != RecordingAudioSealKind.NORMAL;
    }

    public void validateNormalSeal(
            int lastSequence,
            long frames
    ) {
        if ((isSealed() && sealKind != RecordingAudioSealKind.NORMAL) || lastSequence != getLastSequence()
                || frames != totalFrameCount) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SEAL_CONFLICT);
        }
    }

    public void validateClientWrite(Instant now) {
        validateNotFrozen();
        if (isSealed() && !now.isBefore(acceptUntil)) {
            throw new RecordingException(RecordingErrorCode.AUDIO_ACCEPTANCE_CLOSED);
        }
    }

    public Instant capUploadExpiry(Instant requested) {
        if (isSealed() && requested.isAfter(acceptUntil)) {
            return acceptUntil;
        }
        return requested;
    }

    public void freeze() {
        frozen = true;
    }

    public void validateNotFrozen() {
        if (frozen) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SAVE_FROZEN);
        }
    }
}
