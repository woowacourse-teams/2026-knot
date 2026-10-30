package com.knot.backend.recording.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.recording.application.dto.result.MeetingSummaryTask;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingSummaryWorkerTest {

    private final MeetingSummaryGenerationService service = mock(MeetingSummaryGenerationService.class);
    private final MeetingSummaryGenerator generator = mock(MeetingSummaryGenerator.class);
    private final MeetingSummaryWorker worker = new MeetingSummaryWorker(
            service,
            generator
    );
    private final MeetingSummaryTask task = new MeetingSummaryTask(
            1,
            2,
            UUID.randomUUID(),
            1,
            "회의 원문"
    );

    @Test
    @DisplayName("외부 생성 결과는 요약 완료 저장에만 전달한다")
    void process_success() {
        // given
        when(generator.generate(task.transcript())).thenReturn("요약 결과");
        // when
        worker.process(task);
        // then
        verify(service).succeed(
                task,
                "요약 결과"
        );
        verify(
                service,
                never()
        ).fail(
                any(),
                any(),
                eq(false)
        );
    }

    @Test
    @DisplayName("외부 timeout은 자동 재시도 가능한 실패로 기록한다")
    void process_failure_timeout() {
        // given
        when(generator.generate(task.transcript())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
        // when
        worker.process(task);
        // then
        verify(service).fail(
                task,
                "LLM_TIMEOUT",
                true
        );
    }

    @Test
    @DisplayName("출력 한도 초과는 자동 재시도하지 않는다")
    void process_failure_outputLimit() {
        // given
        when(generator.generate(task.transcript())).thenThrow(new LlmException(LlmErrorCode.LLM_OUTPUT_LIMIT_EXCEEDED));
        // when
        worker.process(task);
        // then
        verify(service).fail(
                task,
                "LLM_OUTPUT_LIMIT_EXCEEDED",
                false
        );
    }

    @Test
    @DisplayName("요약 형식 오류는 제한된 자동 재시도로 접수한다")
    void process_invalidSummary_retries() {
        when(generator.generate(task.transcript()))
                .thenThrow(new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY));

        worker.process(task);

        verify(service).fail(
                task,
                "INVALID_MEETING_SUMMARY",
                true
        );
    }

    @Test
    @DisplayName("잘못된 모델 JSON도 제한된 자동 재시도로 접수한다")
    void process_invalidModelResponse_retries() {
        when(generator.generate(task.transcript())).thenThrow(new LlmException(LlmErrorCode.LLM_INVALID_RESPONSE));

        worker.process(task);

        verify(service).fail(
                task,
                "LLM_INVALID_RESPONSE",
                true
        );
    }

    @Test
    @DisplayName("선점 오류가 한 건 발생해도 다음 요약을 계속 처리한다")
    void runOnce_success_isolatesClaimFailure() {
        // given
        when(service.findRunnable()).thenReturn(
                List.of(
                        3L,
                        1L
                )
        );
        when(service.claim(3)).thenThrow(new IllegalStateException("storage"));
        when(service.claim(1)).thenReturn(Optional.of(task));
        when(generator.generate(task.transcript())).thenReturn("요약 결과");
        // when
        worker.runOnce();
        // then
        verify(service).succeed(
                task,
                "요약 결과"
        );
    }
}
