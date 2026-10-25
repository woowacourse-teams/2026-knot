package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecordingTitleTask;
import com.knot.backend.recording.domain.model.RecordingTitleGeneration;
import com.knot.backend.recording.domain.repository.RecordingTitleGenerationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingTitleGenerationService {
    private static final int BATCH_LIMIT = 20;
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);
    private static final String INPUT_UNAVAILABLE = "INPUT_UNAVAILABLE";

    private final RecordingTitleGenerationRepository recordingTitleGenerationRepository;
    private final Clock clock;

    @Transactional
    public void registerEligible() {
        recordingTitleGenerationRepository.registerEligible(
                now(),
                BATCH_LIMIT
        );
    }

    @Transactional(readOnly = true)
    public List<Long> findRunnable() {
        return recordingTitleGenerationRepository.findRunnableRecordingIds(
                now(),
                BATCH_LIMIT
        );
    }

    @Transactional
    public Optional<RecordingTitleTask> claim(long recordingId) {
        Optional<RecordingTitleGeneration> found = recordingTitleGenerationRepository
                .findByRecordingIdForUpdate(recordingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordingTitleGeneration generation = found.orElseThrow();
        Instant now = now();
        if (!generation.claim(
                now,
                LEASE
        )) {
            return Optional.empty();
        }
        Optional<String> transcript = recordingTitleGenerationRepository.findTranscript(recordingId);
        if (transcript.isEmpty()) {
            generation.fail(
                    generation.getAttemptCount(),
                    INPUT_UNAVAILABLE,
                    now,
                    RETRY_BACKOFF
            );
            return Optional.empty();
        }
        return Optional.of(
                new RecordingTitleTask(
                        recordingId,
                        generation.getAttemptCount(),
                        transcript.orElseThrow()
                )
        );
    }

    @Transactional
    public void succeed(
            RecordingTitleTask task,
            String generatedTitle
    ) {
        recordingTitleGenerationRepository.findByRecordingIdForUpdate(task.recordingId())
                .ifPresent(
                        generation -> generation.succeed(
                                task.attempt(),
                                generatedTitle,
                                now()
                        )
                );
    }

    @Transactional
    public void fail(
            RecordingTitleTask task,
            String failureCode
    ) {
        recordingTitleGenerationRepository.findByRecordingIdForUpdate(task.recordingId())
                .ifPresent(
                        generation -> generation.fail(
                                task.attempt(),
                                failureCode,
                                now(),
                                RETRY_BACKOFF
                        )
                );
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
