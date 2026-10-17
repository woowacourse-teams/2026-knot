package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import ch.qos.logback.classic.Level;
import com.knot.backend.recording.application.dto.result.RecordingAudioDeletionExecution;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.testsupport.LogCapture;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingAudioDeletionWorkerLoggingTest {

    @Test
    @DisplayName("삭제와 실패 상태 저장이 연달아 실패해도 삭제 원인을 먼저 남긴다")
    void execute_failure_storageAndPersistence() {
        // given
        RecordingAudioDeletionStateService recordingAudioDeletionStateService = mock(
                RecordingAudioDeletionStateService.class
        );
        RecordingAudioStorage recordingAudioStorage = mock(RecordingAudioStorage.class);
        when(recordingAudioDeletionStateService.claim(7)).thenReturn(
                Optional.of(
                        new RecordingAudioDeletionExecution(
                                7,
                                2,
                                "private-s3-key"
                        )
                )
        );
        doThrow(
                new RecordingException(
                        RecordingErrorCode.AUDIO_STORAGE_UNAVAILABLE,
                        new IOException("private-provider-body")
                )
        ).when(recordingAudioStorage)
                .deleteStoredObject("private-s3-key");
        IllegalStateException persistence = new IllegalStateException("private-sql");
        doThrow(persistence).when(recordingAudioDeletionStateService)
                .fail(
                        7,
                        2
                );
        try (LogCapture logs = new LogCapture(RecordingAudioDeletionWorker.class)) {
            // when
            assertThatThrownBy(
                    () -> new RecordingAudioDeletionWorker(
                            recordingAudioDeletionStateService,
                            recordingAudioStorage,
                            mock(RecordingAudioCleanupStateService.class)
                    ).execute(7)
            ).isSameAs(persistence);
            // then
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains(
                                "taskId=7",
                                "attempt=2",
                                "IOException"
                        )
                                .doesNotContain(
                                        "private-s3-key",
                                        "private-provider-body",
                                        "private-sql"
                                );
                    });
        }
        verify(
                recordingAudioDeletionStateService,
                never()
        ).succeed(
                7,
                2
        );
    }

    @Test
    @DisplayName("이미 사라진 객체의 정상 삭제는 로그 없이 성공 상태를 저장한다")
    void execute_success_absentObject() {
        // given
        RecordingAudioDeletionStateService recordingAudioDeletionStateService = mock(
                RecordingAudioDeletionStateService.class
        );
        when(recordingAudioDeletionStateService.claim(7)).thenReturn(
                Optional.of(
                        new RecordingAudioDeletionExecution(
                                7,
                                2,
                                "private-s3-key"
                        )
                )
        );
        try (LogCapture logs = new LogCapture(RecordingAudioDeletionWorker.class)) {
            // when
            new RecordingAudioDeletionWorker(
                    recordingAudioDeletionStateService,
                    mock(RecordingAudioStorage.class),
                    mock(RecordingAudioCleanupStateService.class)
            ).execute(7);
            // then
            assertThat(logs.events()).isEmpty();
        }
        verify(recordingAudioDeletionStateService).succeed(
                7,
                2
        );
    }
}
