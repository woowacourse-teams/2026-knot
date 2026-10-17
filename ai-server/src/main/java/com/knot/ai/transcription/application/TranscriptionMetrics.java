package com.knot.ai.transcription.application;

import com.knot.ai.transcription.domain.TranscriptionErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TranscriptionMetrics {
    private final long[] jobsCompleted = new long[Outcome.values().length];
    private final long[][] stageCounts = new long[Stage.values().length][Outcome.values().length];
    private final double[][] stageSeconds = new double[Stage.values().length][Outcome.values().length];
    private long jobStarted;
    private Stage activeStage;
    private long stageStarted;

    public synchronized void startJob() {
        jobStarted = System.nanoTime();
    }

    public synchronized void finishJob(boolean succeeded) {
        jobsCompleted[(succeeded ? Outcome.COMPLETED : Outcome.FAILED).ordinal()]++;
        jobStarted = 0;
    }

    public <T> T stage(
            Stage stage,
            UUID attemptId,
            TranscriptionErrorCode fallback,
            TranscriptionLog.Action<T> action
    ) throws Exception {
        long started = System.nanoTime();
        synchronized (this) {
            activeStage = stage;
            stageStarted = started;
        }
        Outcome outcome = Outcome.FAILED;
        try {
            T result = TranscriptionLog.stage(
                    "recording.transcription." + stage.label(),
                    attemptId,
                    fallback,
                    action
            );
            outcome = Outcome.COMPLETED;
            return result;
        } finally {
            synchronized (this) {
                stageCounts[stage.ordinal()][outcome.ordinal()]++;
                stageSeconds[stage.ordinal()][outcome.ordinal()] += elapsed(started);
                activeStage = null;
                stageStarted = 0;
            }
        }
    }

    public synchronized String render(
            boolean ready,
            boolean active
    ) {
        StringBuilder text = new StringBuilder();
        metadata(
                text,
                "knot_ai_ready",
                "Existing AI readiness, independent of a busy worker",
                "gauge"
        );
        sample(
                text,
                "knot_ai_ready",
                "",
                ready ? 1 : 0
        );
        metadata(
                text,
                "knot_ai_active_jobs",
                "Worker active including cleanup",
                "gauge"
        );
        sample(
                text,
                "knot_ai_active_jobs",
                "",
                active ? 1 : 0
        );
        metadata(
                text,
                "knot_ai_job_elapsed_seconds",
                "Time since admitting the current job, zero when idle",
                "gauge"
        );
        sample(
                text,
                "knot_ai_job_elapsed_seconds",
                "",
                active ? elapsed(jobStarted) : 0
        );
        metadata(
                text,
                "knot_ai_stage_active",
                "Current bounded transcription stage",
                "gauge"
        );
        for (Stage stage : Stage.values()) {
            sample(
                    text,
                    "knot_ai_stage_active",
                    stageLabels(stage),
                    activeStage == stage ? 1 : 0
            );
        }
        metadata(
                text,
                "knot_ai_stage_elapsed_seconds",
                "Time in the active stage, zero for other stages",
                "gauge"
        );
        for (Stage stage : Stage.values()) {
            sample(
                    text,
                    "knot_ai_stage_elapsed_seconds",
                    stageLabels(stage),
                    activeStage == stage ? elapsed(stageStarted) : 0
            );
        }
        metadata(
                text,
                "knot_ai_jobs_completed_total",
                "Terminal jobs published after cleanup, including executor rejection",
                "counter"
        );
        for (Outcome outcome : Outcome.values()) {
            sample(
                    text,
                    "knot_ai_jobs_completed_total",
                    "{outcome=\"" + outcome.label() + "\"}",
                    jobsCompleted[outcome.ordinal()]
            );
        }
        metadata(
                text,
                "knot_ai_stage_duration_seconds",
                "Completed or failed stage durations",
                "summary"
        );
        for (Stage stage : Stage.values()) {
            for (Outcome outcome : Outcome.values()) {
                String labels = "{stage=\"" + stage.label() + "\",outcome=\"" + outcome.label() + "\"}";
                sample(
                        text,
                        "knot_ai_stage_duration_seconds_sum",
                        labels,
                        stageSeconds[stage.ordinal()][outcome.ordinal()]
                );
                sample(
                        text,
                        "knot_ai_stage_duration_seconds_count",
                        labels,
                        stageCounts[stage.ordinal()][outcome.ordinal()]
                );
            }
        }
        return text.toString();
    }

    private double elapsed(long started) {
        return Math.max(
                0,
                (System.nanoTime() - started) / 1_000_000_000.0
        );
    }

    private String stageLabels(Stage stage) {
        return "{stage=\"" + stage.label() + "\"}";
    }

    private void metadata(
            StringBuilder text,
            String name,
            String help,
            String type
    ) {
        text.append("# HELP ")
                .append(name)
                .append(' ')
                .append(help)
                .append('\n');
        text.append("# TYPE ")
                .append(name)
                .append(' ')
                .append(type)
                .append('\n');
    }

    private void sample(
            StringBuilder text,
            String name,
            String labels,
            double number
    ) {
        text.append(name)
                .append(labels)
                .append(' ')
                .append(number)
                .append('\n');
    }

    public enum Stage {
        DOWNLOAD,
        DECODE,
        MODEL,
        RESULT_CHECK,
        CLEANUP;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private enum Outcome {
        COMPLETED,
        FAILED;

        private String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
