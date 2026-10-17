package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.recording.domain.AudioFinalizationLeaseLostException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingAudioFinalizationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T01:00:00Z");

    @Test
    void expiredOwnerCannotMutateAnyPhaseAfterReclaim() {
        // given
        RecordingAudioFinalization job = RecordingAudioFinalization.create(
                1,
                NOW
        );
        UUID old = UUID.randomUUID();
        job.claim(
                old,
                NOW,
                Duration.ofSeconds(60)
        );
        long oldEpoch = job.getClaimEpoch();
        UUID current = UUID.randomUUID();
        job.claim(
                current,
                NOW.plusSeconds(60),
                Duration.ofSeconds(60)
        );

        // when
        Throwable failure = catchThrowable(
                () -> job.renew(
                        old,
                        oldEpoch,
                        NOW.plusSeconds(61),
                        Duration.ofSeconds(60)
                )
        );

        // then
        assertThat(failure).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                catchThrowable(
                        () -> job.freeze(
                                old,
                                oldEpoch,
                                NOW.plusSeconds(61),
                                "[]",
                                "hash",
                                "COMPLETE"
                        )
                )
        ).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                catchThrowable(
                        () -> job.fail(
                                old,
                                oldEpoch,
                                NOW.plusSeconds(61),
                                "failure",
                                true
                        )
                )
        ).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(
                catchThrowable(
                        () -> job.ready(
                                old,
                                oldEpoch,
                                NOW.plusSeconds(61),
                                stored()
                        )
                )
        ).isInstanceOf(AudioFinalizationLeaseLostException.class);
        assertThat(job.getOwnerToken()).isEqualTo(current);
    }

    @Test
    void boundedTransientRetriesUseOneTwoFourSecondsThenFail() {
        // given
        RecordingAudioFinalization job = RecordingAudioFinalization.create(
                1,
                NOW
        );
        UUID owner = UUID.randomUUID();
        job.claim(
                owner,
                NOW,
                Duration.ofSeconds(60)
        );

        // when
        job.fail(
                owner,
                job.getClaimEpoch(),
                NOW,
                "AUDIO_STORAGE_UNAVAILABLE",
                true
        );

        // then
        assertThat(job.getNextRunAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(
                job.claim(
                        owner,
                        NOW.plusSeconds(1),
                        Duration.ofSeconds(60)
                )
        ).isTrue();
        job.fail(
                owner,
                job.getClaimEpoch(),
                NOW.plusSeconds(1),
                "AUDIO_STORAGE_UNAVAILABLE",
                true
        );
        assertThat(job.getNextRunAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(
                job.claim(
                        owner,
                        NOW.plusSeconds(3),
                        Duration.ofSeconds(60)
                )
        ).isTrue();
        job.fail(
                owner,
                job.getClaimEpoch(),
                NOW.plusSeconds(3),
                "AUDIO_STORAGE_UNAVAILABLE",
                true
        );
        assertThat(job.getNextRunAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(
                job.claim(
                        owner,
                        NOW.plusSeconds(7),
                        Duration.ofSeconds(60)
                )
        ).isTrue();
        job.fail(
                owner,
                job.getClaimEpoch(),
                NOW.plusSeconds(7),
                "AUDIO_STORAGE_UNAVAILABLE",
                true
        );
        assertThat(job.getStatus()).isEqualTo(AudioFinalizationStatus.FAILED);
        assertThat(job.canRetry()).isTrue();
    }

    @Test
    void retryPreservesFrozenManifestAndLogicalJob() {
        // given
        RecordingAudioFinalization job = RecordingAudioFinalization.create(
                1,
                NOW
        );
        UUID owner = UUID.randomUUID();
        job.claim(
                owner,
                NOW,
                Duration.ofSeconds(60)
        );
        job.freeze(
                owner,
                job.getClaimEpoch(),
                NOW,
                "manifest",
                "hash",
                "PARTIAL"
        );
        for (int index = 0; index < 4; index++) {
            if (index > 0) {
                job.claim(
                        owner,
                        NOW.plusSeconds(index * 10L),
                        Duration.ofSeconds(60)
                );
            }
            job.fail(
                    owner,
                    job.getClaimEpoch(),
                    NOW.plusSeconds(index * 10L),
                    "AUDIO_STORAGE_UNAVAILABLE",
                    true
            );
        }
        long epoch = job.getClaimEpoch();

        // when
        boolean queued = job.retry(NOW.plusSeconds(40));

        // then
        assertThat(queued).isTrue();
        assertThat(job.getManifestJson()).isEqualTo("manifest");
        assertThat(job.getManifestHash()).isEqualTo("hash");
        assertThat(job.getClaimEpoch()).isGreaterThan(epoch);
        assertThat(job.getStatus()).isEqualTo(AudioFinalizationStatus.QUEUED);
        assertThat(job.retry(NOW.plusSeconds(41))).isFalse();
    }

    private StoredAudioChunk stored() {
        return new StoredAudioChunk(
                true,
                46,
                "audio/wav",
                "hash",
                "v1"
        );
    }
}
