package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Level;
import com.knot.backend.testsupport.LogCapture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.DocumentRetentionCleanupService;
import com.knot.backend.document.domain.model.DocumentRetentionCandidate;
import com.knot.backend.document.domain.repository.DocumentRetentionRepository;
import com.knot.backend.global.config.RetentionCleanupProperties;
import com.knot.backend.recording.application.RecordingAudioDeletionWorker;
import com.knot.backend.recording.application.RecordingAudioRetentionService;
import com.knot.backend.recording.domain.repository.RecordingAudioDeletionRepository;
import java.time.Clock;
import java.time.Duration;
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

    private DocumentRetentionRepository documents;
    private DocumentRetentionCleanupService cleanup;
    private RecordingAudioDeletionRepository audio;
    private RecordingAudioRetentionService audioRetention;
    private RecordingAudioDeletionWorker worker;
    private com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository audioCleanup;
    private com.knot.backend.recording.application.RecordingAudioCleanupStateService audioCleanupState;
    private ThreadPoolTaskExecutor executor;
    private RetentionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRetentionRepository.class);
        cleanup = mock(DocumentRetentionCleanupService.class);
        audio = mock(RecordingAudioDeletionRepository.class);
        audioRetention = mock(RecordingAudioRetentionService.class);
        worker = mock(RecordingAudioDeletionWorker.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        audioCleanup = mock(com.knot.backend.recording.domain.repository.RecordingAudioCleanupRepository.class);
        audioCleanupState = mock(com.knot.backend.recording.application.RecordingAudioCleanupStateService.class);
        scheduler = new RetentionCleanupScheduler(
                documents,
                cleanup,
                audio,
                audioRetention,
                worker,
                new RetentionCleanupProperties(),
                executor,
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                audioCleanup,
                audioCleanupState
        );
    }

    @Test
    @DisplayName("개별 후보 정리가 실패해도 다음 녹음과 오디오 접수를 계속한다")
    void cleanupExpiredData_failureContinuesNextCandidate() {
        // given
        when(
                documents.findCandidates(
                        NOW,
                        20
                )
        ).thenReturn(
                List.of(
                        new DocumentRetentionCandidate(
                                1,
                                2,
                                3
                        ),
                        new DocumentRetentionCandidate(
                                1,
                                4,
                                5
                        )
                )
        );
        when(
                audio.findRetentionCandidates(
                        NOW.minus(Duration.ofDays(30)),
                        20
                )
        ).thenReturn(List.of(10L));
        doThrow(new RuntimeException("DB 오류")).when(cleanup)
                .cleanup(
                        1,
                        2,
                        3
                );

        // when
        scheduler.cleanupExpiredData();

        // then
        verify(cleanup).cleanup(
                1,
                4,
                5
        );
        verify(audioRetention).schedule(10);
    }

    @Test
    @DisplayName("실행 슬롯에 제출한 후에만 삭제 Worker를 호출한다")
    void dispatchAudioDeletion_successSubmittedBeforeClaim() {
        // given
        when(
                audio.findReadyTasks(
                        NOW,
                        20
                )
        ).thenReturn(List.of(10L));

        // when
        scheduler.dispatchAudioDeletion();

        // then
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(runnable.capture());
        verifyNoInteractions(worker);
        runnable.getValue()
                .run();
        verify(worker).execute(10);
    }

    @Test
    @DisplayName("실행 슬롯이 없으면 작업 상태를 바꾸거나 Worker를 실행하지 않는다")
    void dispatchAudioDeletion_failureRejectedTaskUnchanged() {
        // given
        when(
                audio.findReadyTasks(
                        NOW,
                        20
                )
        ).thenReturn(
                List.of(
                        10L,
                        11L
                )
        );
        doThrow(new TaskRejectedException("실행 슬롯 없음")).when(executor)
                .execute(any(Runnable.class));

        // when
        scheduler.dispatchAudioDeletion();

        // then
        verifyNoInteractions(worker);
        verify(
                audio,
                never()
        ).findTaskForUpdate(10);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"documents", "audio-retention", "audio-delete"})
    @DisplayName("보존 정리의 세 후보 조회 오류는 기록 후 다음 실행을 계속한다")
    void poll_failure_queryThenNextRun(String phase) {
        // given
        IllegalStateException failure = new IllegalStateException("private-sql");
        Runnable poll = configureFailingQuery(
                phase,
                failure
        );
        try (LogCapture logs = new LogCapture(RetentionCleanupScheduler.class)) {
            poll.run();
            // when
            poll.run();
            // then
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains("phase=")
                                .doesNotContain("private-sql");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        }
        verifyQueryTwice(phase);
    }

    @Test
    void cleanupPrefixSchedulingFailureContinuesNextCandidate() {
        // given
        when(
                audioCleanup.findCandidates(
                        NOW,
                        20
                )
        ).thenReturn(
                List.of(
                        1L,
                        2L
                )
        );
        doThrow(new IllegalStateException("정리 상태 저장 실패")).when(audioCleanupState)
                .schedule(1);
        // when
        scheduler.cleanupExpiredData();
        // then
        verify(audioCleanupState).schedule(2);
    }

    @Test
    void dispatchPrefixDeletionSubmitsBeforeWorkerClaim() {
        // given
        when(
                audioCleanup.findReady(
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
                audioCleanup.findReady(
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

    private Runnable configureFailingQuery(
            String phase,
            IllegalStateException failure
    ) {
        if ("documents".equals(phase)) {
            when(
                    documents.findCandidates(
                            NOW,
                            20
                    )
            ).thenThrow(failure)
                    .thenReturn(List.of());
            return scheduler::cleanupExpiredData;
        }
        if ("audio-retention".equals(phase)) {
            when(
                    audio.findRetentionCandidates(
                            NOW.minus(Duration.ofDays(30)),
                            20
                    )
            ).thenThrow(failure)
                    .thenReturn(List.of());
            return scheduler::cleanupExpiredData;
        }
        when(
                audio.findReadyTasks(
                        NOW,
                        20
                )
        ).thenThrow(failure)
                .thenReturn(List.of());
        return scheduler::dispatchAudioDeletion;
    }

    private void verifyQueryTwice(String phase) {
        if ("documents".equals(phase)) {
            verify(
                    documents,
                    org.mockito.Mockito.times(2)
            ).findCandidates(
                    NOW,
                    20
            );
            return;
        }
        if ("audio-retention".equals(phase)) {
            verify(
                    audio,
                    org.mockito.Mockito.times(2)
            ).findRetentionCandidates(
                    NOW.minus(Duration.ofDays(30)),
                    20
            );
            return;
        }
        verify(
                audio,
                org.mockito.Mockito.times(2)
        ).findReadyTasks(
                NOW,
                20
        );
    }

}
