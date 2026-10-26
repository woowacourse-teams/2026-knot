package com.knot.backend.recording.application;

import com.knot.backend.document.application.DocumentGenerationProgressService;
import com.knot.backend.document.application.dto.result.DocumentGenerationStatusResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingResult;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 녹음 문서와 녹음 기록 목록의 행이 같은 규칙으로 처리 상태와 재시도 가능 여부를 정한다.
@Component
@RequiredArgsConstructor
public class RecordingDocumentProcessing {
    private final DocumentGenerationProgressService documentProgress;
    private final RecordingTranscriptionFailure transcriptionFailure;
    private final Clock clock;

    public RecordingDocumentProcessingResult find(
            long workspaceId,
            long recordingId
    ) {
        return findAll(
                workspaceId,
                List.of(recordingId)
        ).get(recordingId);
    }

    // 문서 정리가 접수되면 그 상태를 따른다. 대기·실행 중인 Job이 있으면 배치가 QUEUED·RUNNING이라 실패보다 먼저
    // PROCESSING이다.
    // 접수 전에는 현재 전사 시도가 실패했을 때만 전사 실패이고, 그 밖은 음성 최종화·전사 대기·실행 중이다.
    // 회의 전체 요약의 진행은 처리 상태에 넣지 않는다. 녹음 묶음은 녹음 수와 관계없이 저장소를 일정한 횟수로 읽는다.
    public Map<Long, RecordingDocumentProcessingResult> findAll(
            long workspaceId,
            List<Long> recordingIds
    ) {
        Map<Long, DocumentGenerationStatusResult> documents = documentProgress.findRecordingProgresses(
                workspaceId,
                recordingIds,
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
        List<Long> beforeDocumentGeneration = recordingIds.stream()
                .filter(recordingId -> !documents.containsKey(recordingId))
                .toList();
        Set<Long> transcriptionFailed = transcriptionFailure.findFailed(beforeDocumentGeneration);
        Set<Long> transcriptionRetryable = transcriptionFailure.findRetryable(transcriptionFailed);
        return recordingIds.stream()
                .distinct()
                .collect(
                        Collectors.toMap(
                                Function.identity(),
                                recordingId -> processing(
                                        documents.get(recordingId),
                                        transcriptionFailed.contains(recordingId),
                                        transcriptionRetryable.contains(recordingId)
                                )
                        )
                );
    }

    private RecordingDocumentProcessingResult processing(
            DocumentGenerationStatusResult documents,
            boolean transcriptionFailed,
            boolean transcriptionRetryable
    ) {
        if (documents != null) {
            return RecordingDocumentProcessingResult.from(documents);
        }
        if (transcriptionFailed) {
            return RecordingDocumentProcessingResult.transcriptionFailed(transcriptionRetryable);
        }
        return RecordingDocumentProcessingResult.processing();
    }
}
