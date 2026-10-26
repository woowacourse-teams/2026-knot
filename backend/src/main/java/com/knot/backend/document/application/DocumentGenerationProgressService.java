package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationProgressResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationHomeResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationStatusResult;
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
                    return new DocumentGenerationHomeResult(
                            batch.getProcessingStatus(),
                            !visible.isEmpty(),
                            retryableJobIds(
                                    workspaceId,
                                    recordingSessionId,
                                    visible,
                                    now
                            )
                    );
                });
    }

    // 녹음 문서는 홈 진행 카드의 7일 표시 기한과 관계없이 실패 Job을 모두 재시도 대상으로 본다.
    @Transactional(readOnly = true)
    public Optional<DocumentGenerationStatusResult> findRecordingProgress(
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
                        return new DocumentGenerationStatusResult(
                                batch.getProcessingStatus(),
                                List.of()
                        );
                    }
                    List<DocumentGenerationJob> failed = jobs.findAllByBatchId(batch.getId())
                            .stream()
                            .filter(DocumentGenerationJob::isFailed)
                            .toList();
                    return new DocumentGenerationStatusResult(
                            batch.getProcessingStatus(),
                            retryableJobIds(
                                    workspaceId,
                                    recordingSessionId,
                                    failed,
                                    now
                            )
                    );
                });
    }

    // 문서 생성 Job 재시도 접수 조건: 사용자 재시도가 가능한 FAILED Job이고 저장된 입력 원문을 쓸 수 있다.
    private List<Long> retryableJobIds(
            long workspaceId,
            long recordingSessionId,
            List<DocumentGenerationJob> failed,
            Instant now
    ) {
        java.util.Map<Long, Boolean> usableInputs = new java.util.HashMap<>();
        return failed.stream()
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
