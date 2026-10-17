package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.AudioSequenceGapException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import jakarta.persistence.Column;
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

    public void freeze() {
        frozen = true;
    }

    public void validateNotFrozen() {
        if (frozen) {
            throw new RecordingException(RecordingErrorCode.AUDIO_SAVE_FROZEN);
        }
    }
}
