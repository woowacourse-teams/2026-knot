package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.MeetingSummaryTask;
import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.workspace.domain.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingSummaryGenerationService {
    private static final int BATCH_LIMIT = 20;
    private static final Duration LEASE = Duration.ofSeconds(180);

    private final RecordingMeetingSummaryRepository summaries;
    private final WorkspaceRepository workspaces;
    private final RecordingSessionRepository recordings;
    private final Clock clock;

    @Transactional
    public void registerEligible() {
        Instant now = now();
        summaries.recordActivation(now);
        summaries.registerEligible(
                now,
                BATCH_LIMIT
        );
    }

    @Transactional(readOnly = true)
    public List<Long> findRunnable() {
        return summaries.findRunnable(
                now(),
                BATCH_LIMIT
        );
    }

    @Transactional
    public Optional<MeetingSummaryTask> claim(long recordingId) {
        Optional<RecordingMeetingSummary> found = lock(recordingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordingMeetingSummary summary = found.orElseThrow();
        Instant now = now();
        Optional<UUID> token = summary.claim(
                now,
                LEASE
        );
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> input = summaries.findUsableTranscript(
                recordingId,
                summary.getTranscriptId()
        );
        if (input.isEmpty()) {
            summary.fail(
                    token.orElseThrow(),
                    "INPUT_UNAVAILABLE",
                    false,
                    now
            );
            return Optional.empty();
        }
        return Optional.of(
                new MeetingSummaryTask(
                        recordingId,
                        summary.getTranscriptId(),
                        token.orElseThrow(),
                        summary.getAttemptNo(),
                        input.orElseThrow()
                )
        );
    }

    @Transactional
    public boolean renew(MeetingSummaryTask task) {
        return lock(task.recordingId()).map(
                summary -> summary.renew(
                        task.token(),
                        now(),
                        LEASE
                )
        )
                .orElse(false);
    }

    @Transactional
    public void succeed(
            MeetingSummaryTask task,
            String content
    ) {
        lock(task.recordingId()).ifPresent(
                summary -> complete(
                        summary,
                        task,
                        content
                )
        );
    }

    @Transactional
    public void fail(
            MeetingSummaryTask task,
            String code,
            boolean transientFailure
    ) {
        lock(task.recordingId()).ifPresent(
                summary -> summary.fail(
                        task.token(),
                        code,
                        transientFailure,
                        now()
                )
        );
    }

    private void complete(
            RecordingMeetingSummary summary,
            MeetingSummaryTask task,
            String content
    ) {
        if (summaries.findUsableTranscript(
                task.recordingId(),
                task.transcriptId()
        )
                .isEmpty()) {
            summary.fail(
                    task.token(),
                    "INPUT_UNAVAILABLE",
                    false,
                    now()
            );
            return;
        }
        summary.succeed(
                task.token(),
                content,
                now()
        );
    }

    private Optional<RecordingMeetingSummary> lock(long recordingId) {
        Optional<Long> workspaceId = summaries.findWorkspaceId(recordingId);
        if (workspaceId.isEmpty()) {
            return Optional.empty();
        }
        if (workspaces.findIncludingDeletedByIdForUpdate(workspaceId.orElseThrow())
                .isEmpty()) {
            return Optional.empty();
        }
        if (recordings.findByIdForUpdate(recordingId)
                .isEmpty()) {
            return Optional.empty();
        }
        return summaries.findByRecordingIdForUpdate(recordingId);
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
