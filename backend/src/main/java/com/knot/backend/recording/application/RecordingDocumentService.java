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
    private final RecordingOutputQuery outputs;

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
        var output = outputs.findAll(
                workspaceId,
                java.util.List.of(recordingId)
        )
                .get(recordingId);
        return RecordingDocumentResult.of(
                recording,
                documentProcessing.find(
                        workspaceId,
                        recordingId
                ),
                meetingSummary(
                        recordingId,
                        output.summaryEligible()
                ),
                output
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

    private RecordingMeetingSummaryResult meetingSummary(
            long recordingId,
            boolean eligible
    ) {
        var saved = meetingSummaries.findByRecordingId(recordingId)
                .map(this::meetingSummary);
        if (saved.isPresent()) {
            return saved.orElseThrow();
        }
        if (eligible) {
            return new RecordingMeetingSummaryResult(
                    com.knot.backend.recording.domain.model.MeetingSummaryStatus.QUEUED,
                    null,
                    false
            );
        }
        return null;
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
