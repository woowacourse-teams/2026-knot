package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.recording.application.RecordingAudioCleanupStateService;
import com.knot.backend.recording.application.RecordingAudioDeletionWorker;
import com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository;
import com.knot.backend.testsupport.LogCapture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class RetentionCleanupSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private RecordingAudioDeletionWorker worker;
    private RecordingAudioCleanupRepository recordingAudioCleanupRepository;
    private RecordingAudioCleanupStateService audioCleanupState;
    private ThreadPoolTaskExecutor executor;
    private RetentionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        worker = mock(RecordingAudioDeletionWorker.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        recordingAudioCleanupRepository = mock(RecordingAudioCleanupRepository.class);
        audioCleanupState = mock(RecordingAudioCleanupStateService.class);
        scheduler = new RetentionCleanupScheduler(
                worker,
                new RetentionCleanupProperties(),
                executor,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                recordingAudioCleanupRepository,
                audioCleanupState
        );
    }

    @Test
    @DisplayName("오디오 정리 후보 조회 오류는 기록 후 다음 실행을 계속한다")
    void scheduleAudioCleanup_failure_queryThenNextRun() {
        // given
        when(recordingAudioCleanupRepository.findCandidates(20)).thenThrow(new IllegalStateException("private-sql"))
                .thenReturn(List.of());
        try (LogCapture logs = new LogCapture(RetentionCleanupScheduler.class)) {
            scheduler.scheduleAudioCleanup();
            // when
            scheduler.scheduleAudioCleanup();
            // then
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains("phase=")
                                .doesNotContain("private-sql");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        }
        verify(
                recordingAudioCleanupRepository,
                times(2)
        ).findCandidates(20);
    }

    @Test
    void cleanupPrefixSchedulingFailureContinuesNextCandidate() {
        // given
        when(recordingAudioCleanupRepository.findCandidates(20)).thenReturn(
                List.of(
                        1L,
                        2L
                )
        );
        doThrow(new IllegalStateException("정리 상태 저장 실패")).when(audioCleanupState)
                .schedule(1);
        // when
        scheduler.scheduleAudioCleanup();
        // then
        verify(audioCleanupState).schedule(2);
    }

    @Test
    void dispatchPrefixDeletionSubmitsBeforeWorkerClaim() {
        // given
        when(
                recordingAudioCleanupRepository.findReady(
                        NOW,
                        20
                )
        ).thenReturn(List.of(1L));
        // when
        scheduler.dispatchAudioPrefixDeletion();
        // then
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verifyNoInteractions(worker);
        task.getValue()
                .run();
        verify(worker).executeCleanup(1);
    }

    @Test
    void rejectedPrefixDeletionSubmissionLeavesClaimInDatabase() {
        // given
        when(
                recordingAudioCleanupRepository.findReady(
                        NOW,
                        20
                )
        ).thenReturn(List.of(1L));
        doThrow(new TaskRejectedException("실행 슬롯 없음")).when(executor)
                .execute(any(Runnable.class));
        // when
        scheduler.dispatchAudioPrefixDeletion();
        // then
        verifyNoInteractions(worker);
        verifyNoInteractions(audioCleanupState);
    }
}
