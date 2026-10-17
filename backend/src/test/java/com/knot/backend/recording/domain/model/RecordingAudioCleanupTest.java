package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingAudioCleanupTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    @Test
    void unadoptedAudioIsNeverClaimed() {
        // given
        RecordingAudioCleanup task = RecordingAudioCleanup.track(
                1,
                NOW
        );
        // when
        boolean claimed = task.claim(
                NOW,
                Duration.ofMinutes(5)
        );
        // then
        assertThat(claimed).isFalse();
        assertThat(task.getScope()).isEqualTo(RecordingAudioCleanupScope.DORMANT);
    }

    @Test
    void adoptedRawCleanupPreservesFinalPrefix() {
        // given
        RecordingAudioCleanup task = RecordingAudioCleanup.track(
                1,
                NOW
        );
        // when
        task.request(
                RecordingAudioCleanupScope.RAW,
                NOW.plusSeconds(120),
                NOW
        );
        // then
        assertThat(task.deletionPrefix()).isEqualTo("recordings/1/chunks/");
    }

    @Test
    void discardPromotesRawCleanupAndInvalidatesOldClaim() {
        // given
        RecordingAudioCleanup task = rawClaim();
        long oldEpoch = task.getClaimEpoch();
        task.request(
                RecordingAudioCleanupScope.ALL,
                NOW.plusSeconds(120),
                NOW.plusSeconds(1)
        );
        // when
        task.finish(
                oldEpoch,
                true,
                NOW.plusSeconds(2)
        );
        // then
        assertThat(task.getLastSweptAt()).isNull();
        assertThat(task.deletionPrefix()).isEqualTo("recordings/1/");
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void expiredClaimCannotAcknowledgeDeletion() {
        // given
        RecordingAudioCleanup task = rawClaim();
        // when
        task.finish(
                task.getClaimEpoch(),
                true,
                NOW.plusSeconds(300)
        );
        // then
        assertThat(task.getLastSweptAt()).isNull();
        assertThat(task.getLeaseUntil()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void successfulSweepKeepsDurableRecheckAfterUrlExpiry() {
        // given
        RecordingAudioCleanup task = rawClaim();
        // when
        task.finish(
                task.getClaimEpoch(),
                true,
                NOW.plusSeconds(200)
        );
        // then
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3800));
        assertThat(task.getLastSweptAt()).isEqualTo(NOW.plusSeconds(200));
        assertThat(task.getScope()).isEqualTo(RecordingAudioCleanupScope.RAW);
    }

    @Test
    void storageFailureRemainsRetryableWithoutSuccessTimestamp() {
        // given
        RecordingAudioCleanup task = rawClaim();
        // when
        task.finish(
                task.getClaimEpoch(),
                false,
                NOW.plusSeconds(10)
        );
        // then
        assertThat(task.getLastSweptAt()).isNull();
        assertThat(task.getLastFailureCode()).isEqualTo("AUDIO_STORAGE_UNAVAILABLE");
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(70));
    }

    @Test
    void successfulSweepBeforeUrlExpiryRechecksSoon() {
        // given
        RecordingAudioCleanup task = rawClaim();
        // when
        task.finish(
                task.getClaimEpoch(),
                true,
                NOW.plusSeconds(10)
        );
        // then
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(70));
    }

    @Test
    void activeLeaseCannotBeClaimedTwice() {
        // given
        RecordingAudioCleanup task = rawClaim();
        // when
        boolean claimed = task.claim(
                NOW.plusSeconds(1),
                Duration.ofMinutes(5)
        );
        // then
        assertThat(claimed).isFalse();
    }

    @Test
    void expiredLeaseCanBeRecoveredByAnotherExecution() {
        // given
        RecordingAudioCleanup task = rawClaim();
        long oldEpoch = task.getClaimEpoch();
        // when
        boolean claimed = task.claim(
                NOW.plusSeconds(300),
                Duration.ofMinutes(5)
        );
        // then
        assertThat(claimed).isTrue();
        assertThat(task.getClaimEpoch()).isGreaterThan(oldEpoch);
    }

    private RecordingAudioCleanup rawClaim() {
        RecordingAudioCleanup task = RecordingAudioCleanup.track(
                1,
                NOW
        );
        task.request(
                RecordingAudioCleanupScope.RAW,
                NOW.plusSeconds(120),
                NOW
        );
        task.claim(
                NOW,
                Duration.ofMinutes(5)
        );
        return task;
    }
}
