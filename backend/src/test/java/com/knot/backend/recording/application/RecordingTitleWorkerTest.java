package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.recording.application.dto.result.RecordingTitleTask;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingTitleWorkerTest {
    private static final RecordingTitleTask TASK = new RecordingTitleTask(
            7L,
            1,
            "회원 탈퇴 정책을 정했다."
    );

    @Mock
    private RecordingTitleGenerationService recordingTitleGenerationService;
    @Mock
    private RecordingTitleGenerator recordingTitleGenerator;
    private RecordingTitleWorker worker;

    @BeforeEach
    void setUp() {
        worker = new RecordingTitleWorker(
                recordingTitleGenerationService,
                recordingTitleGenerator
        );
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    @DisplayName("접수 대상을 먼저 찾고 실행할 작업마다 제목을 만들어 저장한다")
    void runOnce_success() {
        // given
        when(recordingTitleGenerationService.findRunnable()).thenReturn(List.of(7L));
        when(recordingTitleGenerationService.claim(7L)).thenReturn(Optional.of(TASK));
        when(recordingTitleGenerator.generate(TASK.transcript())).thenReturn("회원 탈퇴 정책 논의");

        // when
        worker.runOnce();

        // then
        verify(recordingTitleGenerationService).registerEligible();
        verify(recordingTitleGenerationService).succeed(
                TASK,
                "회원 탈퇴 정책 논의"
        );
    }

    @Test
    @DisplayName("외부 호출이 실패하면 오류 코드로 실패만 기록하고 예외를 밖으로 던지지 않는다")
    void process_fail_externalCall() {
        // given
        when(recordingTitleGenerator.generate(TASK.transcript()))
                .thenThrow(new LlmException(LlmErrorCode.LLM_UNAVAILABLE));

        // when & then
        assertThatCode(() -> worker.process(TASK)).doesNotThrowAnyException();
        verify(
                recordingTitleGenerationService,
                never()
        ).succeed(
                any(),
                anyString()
        );
        verify(recordingTitleGenerationService).fail(
                TASK,
                "LLM_UNAVAILABLE"
        );
    }

    @Test
    @DisplayName("저장할 수 없는 제목이면 제목 오류로 실패를 기록한다")
    void process_fail_invalidTitle() {
        // given
        when(recordingTitleGenerator.generate(TASK.transcript())).thenReturn(" ");
        doThrow(new RecordingException(RecordingErrorCode.INVALID_RECORDING_TITLE))
                .when(recordingTitleGenerationService)
                .succeed(
                        TASK,
                        " "
                );

        // when
        worker.process(TASK);

        // then
        verify(recordingTitleGenerationService).fail(
                TASK,
                "INVALID_RECORDING_TITLE"
        );
    }

    @Test
    @DisplayName("실패 기록마저 실패해도 다음 작업을 막지 않는다")
    void process_fail_failurePersistence() {
        // given
        when(recordingTitleGenerator.generate(TASK.transcript())).thenThrow(new IllegalStateException("boom"));
        doThrow(new IllegalStateException("db")).when(recordingTitleGenerationService)
                .fail(
                        TASK,
                        "INTERNAL"
                );

        // when & then
        assertThatCode(() -> worker.process(TASK)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 작업의 선점이 실패해도 남은 작업을 계속 처리한다")
    void runOnce_success_continuesAfterClaimFailure() {
        // given
        when(recordingTitleGenerationService.findRunnable()).thenReturn(
                List.of(
                        6L,
                        7L
                )
        );
        when(recordingTitleGenerationService.claim(6L)).thenThrow(new IllegalStateException("lock"));
        when(recordingTitleGenerationService.claim(7L)).thenReturn(Optional.of(TASK));
        when(recordingTitleGenerator.generate(TASK.transcript())).thenReturn("회원 탈퇴 정책 논의");

        // when
        worker.runOnce();

        // then
        verify(recordingTitleGenerationService).succeed(
                TASK,
                "회원 탈퇴 정책 논의"
        );
    }
}
