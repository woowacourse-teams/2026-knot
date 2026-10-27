package com.knot.backend.document.application;

import com.knot.backend.document.application.dto.result.DocumentGenerationProgressResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationSummaryResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationHomeResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationStatusResult;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.document.domain.model.DocumentText;
import java.util.List;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
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
                                    visible,
                                    now,
                                    usableInput(
                                            workspaceId,
                                            recordingSessionId
                                    )
                            )
                    );
                });
    }

    // 녹음 문서와 녹음 기록 목록은 홈 진행 카드의 7일 표시 기한과 관계없이 실패 Job을 모두 재시도 대상으로 본다.
    // 녹음 묶음의 배치·실패 Job·입력 원문을 녹음 수와 관계없이 한 번씩 읽는다. 문서 정리가 접수되지 않은 녹음은 결과에 없다.
    @Transactional(readOnly = true)
    public Map<Long, DocumentGenerationStatusResult> findRecordingProgresses(
            long workspaceId,
            Collection<Long> recordingSessionIds,
            Instant now
    ) {
        if (recordingSessionIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentGenerationBatch> found = batches.findAllByWorkspaceIdAndRecordingSessionIdIn(
                workspaceId,
                recordingSessionIds
        );
        Map<Long, List<DocumentGenerationJob>> failedJobs = failedJobsByBatchId(found);
        Map<Long, Long> usableInputRecordings = usableInputRecordings(
                workspaceId,
                failedJobs.values(),
                now
        );
        return found.stream()
                .collect(
                        Collectors.toMap(
                                DocumentGenerationBatch::getRecordingSessionId,
                                batch -> recordingProgress(
                                        batch,
                                        failedJobs.getOrDefault(
                                                batch.getId(),
                                                List.of()
                                        ),
                                        usableInputRecordings,
                                        now
                                )
                        )
                );
    }

    private Map<Long, List<DocumentGenerationJob>> failedJobsByBatchId(List<DocumentGenerationBatch> found) {
        List<Long> failedBatchIds = found.stream()
                .filter(batch -> batch.getProcessingStatus() == DocumentGenerationProcessingStatus.FAILED)
                .map(DocumentGenerationBatch::getId)
                .toList();
        if (failedBatchIds.isEmpty()) {
            return Map.of();
        }
        return jobs.findAllByBatchIdIn(failedBatchIds)
                .stream()
                .filter(DocumentGenerationJob::isFailed)
                .collect(Collectors.groupingBy(DocumentGenerationJob::getBatchId));
    }

    // 지금 재시도할 수 있는 Job의 입력 원문만 읽어, 쓸 수 있는 원문 ID와 그 녹음 ID를 준다.
    private Map<Long, Long> usableInputRecordings(
            long workspaceId,
            Collection<List<DocumentGenerationJob>> failedJobs,
            Instant now
    ) {
        List<Long> transcriptIds = failedJobs.stream()
                .flatMap(List::stream)
                .filter(job -> job.canRetryByUser(now))
                .map(DocumentGenerationJob::getTranscriptId)
                .distinct()
                .toList();
        if (transcriptIds.isEmpty()) {
            return Map.of();
        }
        return inputs.findAll(
                workspaceId,
                transcriptIds
        )
                .stream()
                .filter(input -> !DocumentText.isBlank(input.content()))
                .collect(
                        Collectors.toMap(
                                DocumentGenerationInputResult::transcriptId,
                                DocumentGenerationInputResult::recordingSessionId
                        )
                );
    }

    private DocumentGenerationStatusResult recordingProgress(
            DocumentGenerationBatch batch,
            List<DocumentGenerationJob> failed,
            Map<Long, Long> usableInputRecordings,
            Instant now
    ) {
        if (batch.getProcessingStatus() != DocumentGenerationProcessingStatus.FAILED) {
            return new DocumentGenerationStatusResult(
                    batch.getProcessingStatus(),
                    List.of()
            );
        }
        return new DocumentGenerationStatusResult(
                batch.getProcessingStatus(),
                retryableJobIds(
                        failed,
                        now,
                        transcriptId -> Long.valueOf(batch.getRecordingSessionId())
                                .equals(usableInputRecordings.get(transcriptId))
                )
        );
    }

    // 문서 생성 Job 재시도 접수 조건: 사용자 재시도가 가능한 FAILED Job이고 저장된 입력 원문을 쓸 수 있다.
    private List<Long> retryableJobIds(
            List<DocumentGenerationJob> failed,
            Instant now,
            LongPredicate usableInput
    ) {
        return failed.stream()
                .filter(job -> job.canRetryByUser(now))
                .filter(job -> usableInput.test(job.getTranscriptId()))
                .map(DocumentGenerationJob::getId)
                .distinct()
                .sorted()
                .toList();
    }

    private LongPredicate usableInput(
            long workspaceId,
            long recordingSessionId
    ) {
        Map<Long, Boolean> usableInputs = new HashMap<>();
        return transcriptId -> usableInputs.computeIfAbsent(
                transcriptId,
                id -> hasUsableInput(
                        workspaceId,
                        recordingSessionId,
                        id
                )
        );
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
