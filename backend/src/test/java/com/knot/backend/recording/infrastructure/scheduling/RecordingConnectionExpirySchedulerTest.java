package com.knot.backend.recording.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Level;
import com.knot.backend.testsupport.LogCapture;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.knot.backend.recording.application.RecordingConnectionExpiryService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingConnectionExpirySchedulerTest {

    @Test
    @DisplayName("주기 작업은 설정한 개수만큼 자동 종료 회수를 요청한다")
    void expireDueRecordings_success_usesConfiguredBatchSize() {
        // given
        RecordingConnectionExpiryService recordingConnectionExpiryService = mock(
                RecordingConnectionExpiryService.class
        );
        RecordingConnectionExpiryScheduler scheduler = new RecordingConnectionExpiryScheduler(
                recordingConnectionExpiryService,
                new RecordingConnectionExpiryProperties(
                        true,
                        Duration.ofSeconds(1),
                        100,
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(30)
                )
        );

        // when
        scheduler.expireDueRecordings();

        // then
        verify(recordingConnectionExpiryService).expireDue(100);
    }

    @Test
    @DisplayName("녹음 회수 조회 오류 뒤 다음 실행과 INFO 성공 요약을 유지한다")
    void expireDueRecordings_failure_queryThenNextRun() {
        RecordingConnectionExpiryService service = mock(RecordingConnectionExpiryService.class);
        org.mockito.Mockito.when(service.expireDue(100))
                .thenThrow(new IllegalStateException("private-sql"))
                .thenReturn(2);
        RecordingConnectionExpiryScheduler scheduler = new RecordingConnectionExpiryScheduler(
                service,
                new RecordingConnectionExpiryProperties(
                        true,
                        Duration.ofSeconds(1),
                        100,
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(30)
                )
        );
        try (LogCapture logs = new LogCapture(RecordingConnectionExpiryScheduler.class)) {
            scheduler.expireDueRecordings();
            scheduler.expireDueRecordings();
            assertThat(logs.events()).extracting(event -> event.getLevel())
                    .containsExactly(
                            Level.ERROR,
                            Level.INFO
                    );
            assertThat(logs.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-sql"));
        }
        verify(
                service,
                org.mockito.Mockito.times(2)
        ).expireDue(100);
    }

}
