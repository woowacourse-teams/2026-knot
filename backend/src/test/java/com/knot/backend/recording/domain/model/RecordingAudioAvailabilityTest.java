package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingAudioAvailabilityTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @Test
    void completedAudioRemainsAvailableWithoutAnAgeDeadline() {
        RecordingAudioUpload upload = completedUpload();
        assertThat(upload.getCompletedAt()).isBefore(NOW.minus(Duration.ofDays(30)));
        assertThat(upload.isAudioAvailable()).isTrue();
    }

    @Test
    void revokedAudioIsNotAvailable() {
        RecordingAudioUpload upload = completedUpload();
        upload.revoke(NOW);
        assertThat(upload.isAudioAvailable()).isFalse();
    }

    @Test
    void deletedAudioKeepsFirstDeletionAndCompletionTime() {
        RecordingAudioUpload upload = completedUpload();
        Instant completedAt = upload.getCompletedAt();
        upload.recordDeleted(NOW);
        upload.recordDeleted(NOW.plusSeconds(1));
        assertThat(upload.getDeletedAt()).isEqualTo(NOW);
        assertThat(upload.getCompletedAt()).isEqualTo(completedAt);
        assertThat(upload.isCompleted()).isTrue();
        assertThat(upload.isAudioAvailable()).isFalse();
    }

    @Test
    void reservedAudioIsNotAvailableOrDeletableAsCompletedAudio() {
        RecordingAudioUpload upload = RecordingAudioUpload.reserve(
                1L,
                "key",
                "audio/webm",
                100,
                NOW
        );
        assertThat(upload.isAudioAvailable()).isFalse();
        assertThat(catchThrowable(() -> upload.recordDeleted(NOW))).isInstanceOf(RecordingException.class);
    }

    private RecordingAudioUpload completedUpload() {
        Instant old = NOW.minus(Duration.ofDays(365));
        RecordingAudioUpload upload = RecordingAudioUpload.reserve(
                1L,
                "key",
                "audio/webm",
                100,
                old
        );
        upload.complete(
                StoredAudioObject.of(
                        100,
                        "audio/webm",
                        "version-1"
                ),
                old.plusSeconds(60)
        );
        return upload;
    }
}
