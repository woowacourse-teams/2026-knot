package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationProgressResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationHomeResult;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.document.domain.model.DocumentText;
import java.util.List;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class DocumentGenerationProgressService {
    private final DocumentGenerationBatchRepository batches;
    private final DocumentGenerationJobRepository jobs;
    private final DocumentGenerationInputQuery inputs;

    @Transactional(readOnly = true)
    public Optional<DocumentGenerationHomeResult> findHomeProgress(
            long workspaceId,
            long recordingSessionId,
            Instant now
    ) {
        return batches.findByWorkspaceIdAndRecordingSessionId(
                workspaceId,
                recordingSessionId
        )
                .map(batch -> {
                    if (batch.getProcessingStatus() != DocumentGenerationProcessingStatus.FAILED) {
                        return new DocumentGenerationHomeResult(
                                batch.getProcessingStatus(),
                                false,
                                List.of()
                        );
                    }
                    List<DocumentGenerationJob> visible = jobs.findAllByBatchId(batch.getId())
                            .stream()
                            .filter(DocumentGenerationJob::isFailed)
                            .filter(job -> job.getExpiresAt() != null && now.isBefore(job.getExpiresAt()))
                            .toList();
                    java.util.Map<Long, Boolean> usableInputs = new java.util.HashMap<>();
                    List<Long> retryable = visible.stream()
                            .filter(job -> job.canRetryByUser(now))
                            .filter(
                                    job -> usableInputs.computeIfAbsent(
                                            job.getTranscriptId(),
                                            transcriptId -> hasUsableInput(
                                                    workspaceId,
                                                    recordingSessionId,
                                                    transcriptId
                                            )
                                    )
                            )
                            .map(DocumentGenerationJob::getId)
                            .distinct()
                            .sorted()
                            .toList();
                    return new DocumentGenerationHomeResult(
                            batch.getProcessingStatus(),
                            !visible.isEmpty(),
                            retryable
                    );
                });
    }

    private boolean hasUsableInput(
            long workspaceId,
            long recordingSessionId,
            long transcriptId
    ) {
        return inputs.find(
                workspaceId,
                transcriptId
        )
                .filter(input -> input.recordingSessionId() == recordingSessionId)
                .filter(input -> !DocumentText.isBlank(input.content()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<DocumentGenerationProgressResult> findProgress(
            long workspaceId,
            long recordingSessionId
    ) {
        return batches.findByWorkspaceIdAndRecordingSessionId(
                workspaceId,
                recordingSessionId
        )
                .map(DocumentGenerationProgressResult::from);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<DocumentGenerationSummaryResult> findSummary(
            long workspaceId,
            long recordingSessionId
    ) {
        return batches.findByWorkspaceIdAndRecordingSessionId(
                workspaceId,
                recordingSessionId
        )
                .map(
                        batch -> new DocumentGenerationSummaryResult(
                                batch.getProcessingStatus(),
                                batch.getSucceededCount(),
                                jobs.findAllByBatchId(batch.getId())
                                        .stream()
                                        .filter(DocumentGenerationJob::isFailed)
                                        .map(DocumentGenerationJob::getId)
                                        .toList()
                        )
                );
    }
}
