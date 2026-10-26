package com.knot.backend.recording.application;

import com.knot.backend.document.application.DocumentGenerationProgressService;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentProcessingResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import com.knot.backend.recording.application.dto.result.RecordingMeetingSummaryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingDocumentService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;
    private final DocumentGenerationProgressService documentProgress;
    private final RecordingTranscriptionFailure transcriptionFailure;
    private final RecordingMeetingSummaryRepository meetingSummaries;
    private final Clock clock;

    // 조회는 녹음·전사·제목·요약 상태와 문서 확인 기록을 바꾸지 않고 요약을 새로 접수하지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecordingDocumentResult find(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        RecentRecordingItemResult recording = findOpenable(
                workspaceId,
                recordingId
        );
        return RecordingDocumentResult.of(
                recording,
                processing(
                        workspaceId,
                        recordingId
                ),
                meetingSummary(recordingId)
        );
    }

    private RecentRecordingItemResult findOpenable(
            long workspaceId,
            long recordingId
    ) {
        if (recordingId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        return recentRecordingQuery.findItem(
                workspaceId,
                recordingId
        )
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND));
    }

    // 문서 정리가 접수되면 그 상태를 따른다. 대기·실행 중인 Job이 있으면 배치가 QUEUED·RUNNING이라 실패보다 먼저
    // PROCESSING이다.
    // 접수 전에는 현재 전사 시도가 실패했을 때만 전사 실패이고, 그 밖은 음성 최종화·전사 대기·실행 중이다.
    // 회의 전체 요약의 진행은 처리 상태에 넣지 않는다.
    private RecordingDocumentProcessingResult processing(
            long workspaceId,
            long recordingId
    ) {
        return documentProgress.findRecordingProgress(
                workspaceId,
                recordingId,
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        )
                .map(RecordingDocumentProcessingResult::from)
                .orElseGet(() -> beforeDocumentGeneration(recordingId));
    }

    private RecordingDocumentProcessingResult beforeDocumentGeneration(long recordingId) {
        if (transcriptionFailure.isFailed(recordingId)) {
            return RecordingDocumentProcessingResult.transcriptionFailed(transcriptionFailure.isRetryable(recordingId));
        }
        return RecordingDocumentProcessingResult.processing();
    }

    // 요약 행은 주제가 등록돼 NO_CONTENT가 아님이 정해진 녹음에만 접수된다(#992).
    // 전사 전·전사 실패·NO_CONTENT와 요약 기능을 켜기 전에 끝난 녹음은 행이 없어 null이다.
    private RecordingMeetingSummaryResult meetingSummary(long recordingId) {
        return meetingSummaries.findByRecordingId(recordingId)
                .map(this::meetingSummary)
                .orElse(null);
    }

    private RecordingMeetingSummaryResult meetingSummary(RecordingMeetingSummary summary) {
        return RecordingMeetingSummaryResult.of(
                summary,
                meetingSummaries.findUsableTranscript(
                        summary.getRecordingId(),
                        summary.getTranscriptId()
                )
                        .isPresent()
        );
    }
}
