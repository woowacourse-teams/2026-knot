package com.knot.backend.recording.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.RecordingException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingTranscriptionQueueTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Test
    @DisplayName("새 전사는 고정한 source와 실행 시계 없는 QUEUED로 생성한다")
    void queued_success_immutableInput() {
        // given
        UUID attempt = UUID.randomUUID();
        // when
        RecordingTranscription row = queued(attempt);
        // then
        assertThat(row.getStatus()).isEqualTo(TranscriptionStatus.QUEUED);
        assertThat(row.getStartedAt()).isNull();
        assertThat(row.getExecutionDeadline()).isNull();
        assertThat(row.getSourceStorageKey()).isEqualTo("key");
        assertThat(row.getSourceVersionId()).isEqualTo("version");
        assertThat(row.getSpeakerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 lease가 살아 있으면 다른 owner의 선점을 거절한다")
    void claim_failure_liveOwner() {
        // given
        RecordingTranscription row = queued(UUID.randomUUID());
        row.claim(
                UUID.randomUUID(),
                NOW,
                LEASE
        );
        // when
        Throwable failure = catchThrowable(
                () -> row.claim(
                        UUID.randomUUID(),
                        NOW.plusSeconds(59),
                        LEASE
                )
        );
        // then
        assertThat(failure).isInstanceOf(RecordingException.class);
        assertThat(row.getClaimEpoch()).isEqualTo(1);
    }

    @Test
    @DisplayName("정확히 만료 시각에 재선점하면 같은 attempt의 새 epoch만 유효하다")
    void claim_success_expiredGeneration() {
        // given
        UUID attempt = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        RecordingTranscription row = queued(attempt);
        row.claim(
                oldOwner,
                NOW,
                LEASE
        );
        // when
        row.claim(
                owner,
                NOW.plusSeconds(60),
                LEASE
        );
        // then
        assertThat(row.getClaimEpoch()).isEqualTo(2);
        assertThat(row.getAttemptId()).isEqualTo(attempt);
        assertThat(
                row.isOwnedBy(
                        attempt,
                        oldOwner,
                        1,
                        NOW.plusSeconds(60)
                )
        ).isFalse();
        assertThat(
                row.isOwnedBy(
                        attempt,
                        owner,
                        2,
                        NOW.plusSeconds(60)
                )
        ).isTrue();
    }

    @Test
    @DisplayName("같은 시도에 POST를 다시 접수해도 최초 실행 deadline은 연장하지 않는다")
    void beginRemotePost_success_fixedDeadline() {
        // given
        RecordingTranscription row = queued(UUID.randomUUID());
        row.claim(
                UUID.randomUUID(),
                NOW,
                LEASE
        );
        row.beginRemotePost(
                NOW,
                Duration.ofSeconds(30)
        );
        // when
        row.beginRemotePost(
                NOW.plusSeconds(10),
                Duration.ofHours(1)
        );
        // then
        assertThat(row.getExecutionDeadline()).isEqualTo(NOW.plusSeconds(30));
        assertThat(row.getRemoteObservation()).isEqualTo(RemoteObservation.UNCONFIRMED);
    }

    @Test
    @DisplayName("접수 여부가 미확정인 준비 대기는 기존 실행 deadline을 보존한다")
    void deferReadiness_success_preservesUnconfirmedDeadline() {
        // given
        RecordingTranscription row = queued(UUID.randomUUID());
        row.claim(
                UUID.randomUUID(),
                NOW,
                LEASE
        );
        row.beginRemotePost(
                NOW,
                Duration.ofSeconds(30)
        );
        // when
        boolean deferred = row.deferReadiness(
                NOW,
                Duration.ofSeconds(120)
        );
        // then
        assertThat(deferred).isTrue();
        assertThat(row.getExecutionDeadline()).isEqualTo(NOW.plusSeconds(30));
        assertThat(row.getNextRunAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(row.getOwnerToken()).isNull();
        assertThat(row.getStatus()).isEqualTo(TranscriptionStatus.QUEUED);
    }

    private RecordingTranscription queued(UUID attempt) {
        return RecordingTranscription.queued(
                1,
                attempt,
                NOW,
                "key",
                "version",
                "audio/wav",
                100,
                2
        );
    }
}
