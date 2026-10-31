package com.knot.backend.recording.application;

import com.knot.backend.testsupport.RecordingConnectionPolicyFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingConnectionExpiryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:10:00.123456Z");
    private static final int BATCH_SIZE = 100;

    private RecordingSessionRepository recordingSessionRepository;
    private RecordingConnectionExpiryTransaction recordingConnectionExpiryTransaction;
    private RecordingConnectionExpiryService service;

    @BeforeEach
    void setUp() {
        recordingSessionRepository = mock(RecordingSessionRepository.class);
        recordingConnectionExpiryTransaction = mock(RecordingConnectionExpiryTransaction.class);
        service = new RecordingConnectionExpiryService(
                recordingSessionRepository,
                recordingConnectionExpiryTransaction,
                RecordingConnectionPolicyFixture.DEFAULT,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    @DisplayName("자동 종료 시각이 지난 활성 녹음을 정해진 개수만 찾아 녹음마다 회수하고 종료한 수를 돌려준다")
    void expireDue_success_countsExpired() {
        // given
        when(
                recordingSessionRepository.findActiveIdsDueAt(
                        NOW,
                        NOW.minus(RecordingConnectionPolicyFixture.GRACE),
                        BATCH_SIZE
                )
        ).thenReturn(
                List.of(
                        1L,
                        2L
                )
        );
        when(recordingConnectionExpiryTransaction.expire(1L)).thenReturn(true);
        when(recordingConnectionExpiryTransaction.expire(2L)).thenReturn(false);

        // when
        int expiredCount = service.expireDue(BATCH_SIZE);

        // then
        assertThat(expiredCount).isEqualTo(1);
        verify(recordingConnectionExpiryTransaction).expire(1L);
        verify(recordingConnectionExpiryTransaction).expire(2L);
    }

    @Test
    @DisplayName("한 녹음의 회수가 실패해도 다음 녹음을 계속 회수한다")
    void expireDue_success_isolatesFailure() {
        // given
        when(
                recordingSessionRepository.findActiveIdsDueAt(
                        NOW,
                        NOW.minus(RecordingConnectionPolicyFixture.GRACE),
                        BATCH_SIZE
                )
        ).thenReturn(
                List.of(
                        1L,
                        2L
                )
        );
        when(recordingConnectionExpiryTransaction.expire(1L)).thenThrow(new IllegalStateException("잠금 시간 초과"));
        when(recordingConnectionExpiryTransaction.expire(2L)).thenReturn(true);

        // when
        int expiredCount = service.expireDue(BATCH_SIZE);

        // then
        assertThat(expiredCount).isEqualTo(1);
        verify(recordingConnectionExpiryTransaction).expire(2L);
    }
}
