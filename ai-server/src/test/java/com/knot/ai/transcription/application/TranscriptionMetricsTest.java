package com.knot.ai.transcription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import com.knot.ai.transcription.domain.TranscriptionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptionMetricsTest {
    private final TranscriptionMetrics metrics = new TranscriptionMetrics();

    @Test
    @DisplayName("준비되지 않은 유휴 상태는 모든 단계와 시간을 0으로 표시한다")
    void render_success_idleFiniteExposition() {
        // when
        String text = metrics.render(
                false,
                false
        );

        // then
        assertThat(text).endsWith("\n")
                .contains(
                        "knot_ai_ready 0.0\n",
                        "knot_ai_active_jobs 0.0\n",
                        "knot_ai_job_elapsed_seconds 0.0\n"
                );
        List<String> metadata = text.lines()
                .filter(line -> line.startsWith("# "))
                .toList();
        assertThat(metadata).hasSize(14)
                .doesNotHaveDuplicates();
        for (String line : text.lines()
                .filter(line -> !line.startsWith("#"))
                .toList()) {
            double number = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            assertThat(Double.isFinite(number)).isTrue();
            assertThat(number).isZero();
        }
    }

    @Test
    @DisplayName("실행 중 단계의 지표 조회는 작업이 막혀도 대기하지 않는다")
    void render_success_duringBlockedStage() throws Exception {
        // given
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        metrics.startJob();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(
                    () -> metrics.stage(
                            TranscriptionMetrics.Stage.MODEL,
                            UUID.randomUUID(),
                            TranscriptionErrorCode.INFERENCE_FAILED,
                            () -> {
                                entered.countDown();
                                release.await();
                                return null;
                            }
                    )
            );
            try {
                assertThat(
                        entered.await(
                                2,
                                TimeUnit.SECONDS
                        )
                ).isTrue();

                // when
                String text = metrics.render(
                        true,
                        true
                );

                // then
                assertThat(text).contains(
                        "knot_ai_ready 1.0\n",
                        "knot_ai_active_jobs 1.0\n",
                        "knot_ai_stage_active{stage=\"model\"} 1.0\n",
                        "knot_ai_stage_active{stage=\"decode\"} 0.0\n",
                        "knot_ai_stage_elapsed_seconds{stage=\"decode\"} 0.0\n",
                        "knot_ai_stage_duration_seconds_count{stage=\"model\",outcome=\"completed\"} 0.0\n"
                );
                assertThat(
                        number(
                                text,
                                "knot_ai_stage_elapsed_seconds{stage=\"model\"}"
                        )
                ).isPositive();
                assertThat(
                        number(
                                text,
                                "knot_ai_job_elapsed_seconds"
                        )
                ).isPositive();
                assertThat(running.isDone()).isFalse();
            } finally {
                release.countDown();
                running.get(
                        2,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    @Test
    @DisplayName("정상 종료한 단계는 누적 시간과 횟수를 남기고 활성 시간을 비운다")
    void stage_success_completedDuration() throws Exception {
        // when
        String result = metrics.stage(
                TranscriptionMetrics.Stage.DOWNLOAD,
                UUID.randomUUID(),
                TranscriptionErrorCode.DOWNLOAD_FAILED,
                () -> "completed"
        );

        // then
        String text = metrics.render(
                true,
                false
        );
        assertThat(result).isEqualTo("completed");
        assertThat(text).contains(
                "knot_ai_stage_active{stage=\"download\"} 0.0\n",
                "knot_ai_stage_elapsed_seconds{stage=\"download\"} 0.0\n",
                "knot_ai_stage_duration_seconds_count{stage=\"download\",outcome=\"completed\"} 1.0\n",
                "knot_ai_stage_duration_seconds_count{stage=\"download\",outcome=\"failed\"} 0.0\n"
        );
        assertThat(
                number(
                        text,
                        "knot_ai_stage_duration_seconds_sum{stage=\"download\",outcome=\"completed\"}"
                )
        ).isPositive();
    }

    @Test
    @DisplayName("단계 실패는 원래 예외를 유지하고 실패 시간만 집계한다")
    void stage_failure_preservesExceptionAndDuration() {
        // given
        TranscriptionException failure = new TranscriptionException(TranscriptionErrorCode.DECODE_FAILED);

        // when
        TranscriptionException thrown = assertThrows(
                TranscriptionException.class,
                () -> metrics.stage(
                        TranscriptionMetrics.Stage.DECODE,
                        UUID.randomUUID(),
                        TranscriptionErrorCode.INFERENCE_FAILED,
                        () -> {
                            throw failure;
                        }
                )
        );

        // then
        String text = metrics.render(
                true,
                false
        );
        assertThat(thrown).isSameAs(failure);
        assertThat(text).contains(
                "knot_ai_stage_active{stage=\"decode\"} 0.0\n",
                "knot_ai_stage_duration_seconds_count{stage=\"decode\",outcome=\"failed\"} 1.0\n",
                "knot_ai_stage_duration_seconds_count{stage=\"decode\",outcome=\"completed\"} 0.0\n"
        );
        assertThat(
                number(
                        text,
                        "knot_ai_stage_duration_seconds_sum{stage=\"decode\",outcome=\"failed\"}"
                )
        ).isPositive();
    }

    private double number(
            String text,
            String name
    ) {
        return text.lines()
                .filter(line -> line.startsWith(name + " "))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElseThrow();
    }
}
