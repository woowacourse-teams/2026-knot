package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.recording.application.dto.result.RecordingDocumentResult;
import com.knot.backend.recording.application.dto.result.RecordingMeetingSummaryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingDocumentService {
    private final RecordingAccess recordingAccess;
    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingDocumentProcessing documentProcessing;
    private final RecordingMeetingSummaryRepository meetingSummaries;

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
                documentProcessing.find(
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
