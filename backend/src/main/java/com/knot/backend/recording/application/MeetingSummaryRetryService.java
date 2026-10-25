package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.MeetingSummaryRetryResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.MeetingSummaryStatus;
import com.knot.backend.recording.domain.model.RecordingMeetingSummary;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingSummaryRetryService {

    private final RecordingAccess access;
    private final RecordingMeetingSummaryRepository summaries;
    private final Clock clock;

    @Transactional
    public MeetingSummaryRetryResult retry(
            long workspaceId,
            long memberId,
            long recordingId
    ) {
        access.loadWorkspaceRecordingForUpdate(
                workspaceId,
                memberId,
                recordingId
        );
        if (!summaries.isReadable(recordingId)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_NOT_FOUND);
        }
        RecordingMeetingSummary summary = summaries.findByRecordingIdForUpdate(recordingId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.MEETING_SUMMARY_RETRY_NOT_ALLOWED));
        summary.retry(
                summaries.findUsableTranscript(
                        recordingId,
                        summary.getTranscriptId()
                )
                        .isPresent(),
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );
        return new MeetingSummaryRetryResult(
                recordingId,
                MeetingSummaryStatus.QUEUED
        );
    }
}
